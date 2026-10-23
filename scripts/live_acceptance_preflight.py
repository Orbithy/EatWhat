#!/usr/bin/env python3
"""Read-only checks for an explicitly selected EatWhat acceptance environment.

No application startup, schema migration, object write, event delivery or network
fault injection. Credentials stay in libpq/AWS profiles and local PEM files.
Passing this script is never an end-to-end acceptance result.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import uuid
from urllib.parse import urlsplit


class CheckFailed(Exception):
    pass


def require(condition, message):
    if not condition:
        raise CheckFailed(message)


def origin(value):
    u = urlsplit(value)
    require(u.scheme == "https" and u.hostname and not u.username and not u.password
            and not u.query and not u.fragment and u.path in ("", "/"),
            "Expected an HTTPS origin without credentials or a path")
    return u


def validate(config):
    require(config.get("environment_kind") == "isolated-acceptance",
            "An isolated acceptance environment must be explicitly selected")
    for name, mode in (("campus", "CAMPUS"), ("external", "EXTERNAL")):
        site = config[name]
        require(site["mode"] == mode, "Site mode mismatch")
        require(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", site["site_id"]), "Invalid site ID")
        require(uuid.UUID(site["instance_id"]).int != 0, "Invalid installation UUID")
        require(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", site["pg_service"]), "Invalid libpq service name")
        origin(site["readiness_origin"])
    require(config["campus"]["site_id"] != config["external"]["site_id"], "Site IDs must differ")
    require(config["campus"]["instance_id"] != config["external"]["instance_id"], "Installations must differ")
    require(config["campus"]["pg_service"] != config["external"]["pg_service"], "DB services must differ")
    tls = config["node_tls"]
    origin(tls["external_origin"])
    require(re.fullmatch(r"[0-9a-f]{64}", tls["external_leaf_sha256"]), "Invalid certificate pin")
    for key in ("ca_file", "campus_certificate_file", "campus_private_key_file"):
        require(Path(tls[key]).is_file(), "Missing TLS file: " + key)
    for name in ("rustfs", "r2"):
        store = config[name]
        origin(store["endpoint"])
        require(re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", store["bucket"]), "Invalid bucket")
        require(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", store["aws_profile"]), "Invalid AWS profile")
        require(store["dedicated_test_bucket"] is True, "A dedicated test bucket must be selected")
        require(store["region"], "Missing storage region")
    host = urlsplit(config["r2"]["endpoint"]).hostname
    require(re.fullmatch(r"[0-9a-f]{32}(?:\.(?:eu|us|fedramp))?\.r2\.cloudflarestorage\.com", host),
            "R2 must use its account S3 API endpoint")
    require(config["r2"]["region"] == "auto", "R2 region must be auto")
    return config


def run(command, environment=None):
    require(shutil.which(command[0]), "Missing executable: " + command[0])
    result = subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True,
                            text=True, timeout=30, env=environment)
    # Tool stderr may contain hostnames, access-bearing URLs or database details.
    require(result.returncode == 0, "External read-only probe failed; inspect credentials/connectivity locally")
    return result.stdout


DATABASE_QUERY = """
BEGIN READ ONLY;
SELECT json_build_object(
 'site_id', n.site_id, 'instance_id', n.instance_id, 'mode', n.mode,
 'database_fingerprint', md5(n.cluster_identifier || ':' || n.database_oid::text),
 'v35', EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='35' AND success),
 'coordination', to_regclass('eatwhat_file.file_gc_coordination') IS NOT NULL,
 'reference_guard', to_regprocedure('eatwhat_file.assert_keys_referenceable(text[])') IS NOT NULL,
 'coordination_guard', EXISTS(SELECT 1 FROM pg_trigger WHERE
   tgrelid=to_regclass('eatwhat_file.file_gc_coordination') AND tgname='gc_coordination_guard'
   AND tgenabled IN ('O','A') AND NOT tgisinternal),
 'restricted_role', NOT (r.rolsuper OR r.rolbypassrls))
FROM eatwhat_identity.node_state n JOIN pg_roles r ON r.rolname=current_user WHERE n.singleton;
ROLLBACK;
"""


def database(site):
    environment = dict(os.environ, PGCONNECT_TIMEOUT="5",
                       PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=1000")
    rows = run(["psql", "-X", "-w", "-qAt", "-v", "ON_ERROR_STOP=1", "-d",
                "service=" + site["pg_service"], "-c", DATABASE_QUERY], environment).splitlines()
    require(len(rows) == 1, "Expected one persistent site identity")
    facts = json.loads(rows[0])
    for key in ("site_id", "instance_id", "mode"):
        require(facts[key] == site[key], "Persistent site identity does not match configuration")
    for key in ("v35", "coordination", "reference_guard", "coordination_guard", "restricted_role"):
        require(facts[key] is True, "Database prerequisite failed: " + key)
    return facts


def http_get(url, path, context, expected_status, pin=None):
    u = origin(url)
    connection = http.client.HTTPSConnection(u.hostname, u.port or 443, context=context, timeout=5)
    try:
        connection.connect()
        if pin:
            actual = hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest()
            require(actual == pin, "External leaf certificate pin mismatch")
        connection.request("GET", path, headers={"Connection": "close"})
        response = connection.getresponse()
        body = response.read(4097)
        require(response.status == expected_status, "Unexpected HTTP status")
        require(len(body) <= 4096, "Unexpected response size")
        return json.loads(body)
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.config.resolve() == args.report.resolve():
        parser.error("The report must not overwrite the configuration")
    report = {"schema": 1, "checked_at": datetime.now(timezone.utc).isoformat(),
              "read_only": True, "deployment_performed": False,
              "e2e_status": "NOT_RUN", "checks": [], "preflight": "BLOCKED"}
    fingerprints = []

    def check(name, action):
        try:
            action()
            report["checks"].append({"name": name, "status": "PASS"})
        except Exception as error:
            message = str(error) if isinstance(error, CheckFailed) else type(error).__name__
            report["checks"].append({"name": name, "status": "FAIL", "reason": message})

    try:
        config = validate(json.loads(args.config.read_text()))
    except Exception as error:
        message = str(error) if isinstance(error, CheckFailed) else type(error).__name__
        report["checks"].append({"name": "explicit_configuration", "status": "FAIL", "reason": message})
    else:
        for name in ("campus", "external"):
            site = config[name]
            check(name + "_database", lambda site=site: fingerprints.append(database(site)["database_fingerprint"]))
            def readiness(site=site):
                response = http_get(site["readiness_origin"], "/actuator/health/readiness",
                                    ssl.create_default_context(), 200)
                require(response.get("status") == "UP", "Site is not ready")
            check(name + "_readiness", readiness)
        if len(fingerprints) == 2:
            check("separate_databases", lambda: require(fingerprints[0] != fingerprints[1], "Both sites point at one DB"))
        def node_tls():
            tls = config["node_tls"]
            context = ssl.create_default_context(cafile=tls["ca_file"])
            context.load_cert_chain(tls["campus_certificate_file"], tls["campus_private_key_file"],
                                    password=lambda: os.environ["EATWHAT_ACCEPTANCE_PEM_PASSWORD"])
            body = http_get(tls["external_origin"], "/internal/sync/v1/push", context, 405,
                            tls["external_leaf_sha256"])
            require(body.get("code") == "METHOD_NOT_ALLOWED", "Unexpected node listener response")
        check("pinned_mtls_listener", node_tls)
        for name in ("rustfs", "r2"):
            store = config[name]
            check(name + "_bucket_head", lambda store=store: run([
                "aws", "--profile", store["aws_profile"], "--endpoint-url", store["endpoint"],
                "--region", store["region"], "--cli-connect-timeout", "5", "--cli-read-timeout", "5",
                "--no-cli-pager", "s3api", "head-bucket", "--bucket", store["bucket"]],
                dict(os.environ, AWS_EC2_METADATA_DISABLED="true", AWS_MAX_ATTEMPTS="1")))
        if all(c["status"] == "PASS" for c in report["checks"]):
            report["preflight"] = "PASS"
    args.report.parent.mkdir(parents=True, exist_ok=True)
    # No endpoints, credentials, response bodies, user data or connection strings in the report.
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return 0 if report["preflight"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
