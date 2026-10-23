#!/usr/bin/env python3
"""Fixed SSH deployment agent for existing EatWhat Compose installations (Linux, Python 3.10+)."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid


class Rejected(Exception):
    pass


def require(ok):
    if not ok:
        raise Rejected()


def private_file(path):
    path = Path(path)
    require(path.is_absolute() and not path.is_symlink() and path.is_file())
    require(stat.S_IMODE(path.stat().st_mode) == 0o600)
    return path


def sha_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            digest.update(block)
    return digest.hexdigest()


def atomic_json(path, value):
    atomic_bytes(path, json.dumps(value, separators=(',', ':')).encode())


def atomic_bytes(path, data, mode=0o600):
    fd, temporary = tempfile.mkstemp(prefix='.release-', dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'wb') as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
        directory_sync(path.parent)
    finally:
        Path(temporary).unlink(missing_ok=True)


def directory_sync(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


class Agent:
    def __init__(self, config):
        self.c = json.loads(private_file(config).read_text())
        self.state = Path(self.c['state_directory'])
        require(self.state.is_absolute() and not self.state.is_symlink())
        self.state.mkdir(mode=0o700, parents=True, exist_ok=True)
        require(stat.S_IMODE(self.state.stat().st_mode) == 0o700)
        self.jar = Path(self.c['active_jar'])
        require(self.jar.is_absolute() and self.jar.is_file() and not self.jar.is_symlink())
        self.env_file = private_file(self.c['env_file'])
        self.compose_file = Path(self.c['compose_file'])
        require(self.compose_file.is_absolute() and self.compose_file.is_file() and not self.compose_file.is_symlink())
        self.token = private_file(self.c['node_token_file']).read_text().strip()
        require(re.fullmatch(r'[A-Za-z0-9_-]{43,128}', self.token))
        require(self.c.get('control_host') is False)
        require(self.c.get('restore_evidence_id'))
        require(re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', self.c['project']))
        require(re.fullmatch(r'http://127\.0\.0\.1:[0-9]+', self.c['origin']))
        require(5 <= self.c.get('health_timeout_seconds', 180) <= 600)
        require(5 <= self.c.get('command_timeout_seconds', 180) <= 600)
        docker = self.c.get('docker', '/usr/bin/docker')
        require(Path(docker).is_absolute() and Path(docker).is_file())
        self.base = [docker, 'compose', '--project-name', self.c['project'], '--env-file', str(self.env_file), '-f', str(self.compose_file)]
        # Do not let operator shell exports silently override the reviewed dotenv file.
        self.environment = {'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': str(Path.home()), 'APP_JAR': str(self.jar)}

    def command(self, args, *, output=None, input=None, stdin=None):
        # SSH stdin carries the header and candidate JAR. Compose exec is
        # interactive by default even with -T, so it must never inherit that
        # stream during preflight/runtime hashing. Only an explicit input or
        # file (for restore-format inspection) may be sent to a child command.
        source = stdin if stdin is not None else subprocess.DEVNULL if input is None else None
        result = subprocess.run(self.base + args, input=input, stdin=source, stdout=output if output is not None else subprocess.PIPE,
                                stderr=subprocess.DEVNULL, env=self.environment,
                                timeout=self.c.get('command_timeout_seconds', 180), check=True)
        return result.stdout

    def request(self, path, token=False):
        req = urllib.request.Request(self.c['origin'] + path, headers={'X-Control-Node-Token': self.token} if token else {})
        # A redirect must never move a private node token to another origin.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(req, timeout=3) as response:
            payload = response.read(65537)
            require(response.status == 200 and len(payload) <= 65536)
            return json.loads(payload)

    def identity(self, site, instance):
        snapshot = self.request('/internal/control/v1/snapshot', True)
        require(snapshot.get('siteId') == site and snapshot.get('instanceId') == instance)
        require(snapshot.get('observationStatus') == 'OBSERVED')

    def health(self, site, instance):
        require(self.request('/actuator/health/readiness').get('status') == 'UP')
        self.identity(site, instance)

    def runtime_sha(self):
        # Stream the actual bytes from the running app; an on-host file hash alone is insufficient.
        with tempfile.TemporaryFile() as out:
            self.command(['exec', '-T', 'app', 'cat', '/app/app.jar'], output=out)
            out.seek(0)
            digest = hashlib.sha256()
            for block in iter(lambda: out.read(65536), b''):
                digest.update(block)
            return digest.hexdigest()

    def preflight(self, site, instance, old):
        require(site == self.c['site_id'] and instance == self.c['instance_id'])
        require(sha_file(self.jar) == old and self.runtime_sha() == old)
        self.health(site, instance)
        # Render privately to verify the application mount; never echo the rendered secrets.
        rendered = json.loads(self.command(['config', '--format', 'json']))
        volumes = rendered['services']['app']['volumes']
        require(any(v.get('type') == 'bind' and v.get('target') == '/app/app.jar'
                    and Path(v.get('source', '')).resolve() == self.jar.resolve() for v in volumes))
        require(shutil.disk_usage(self.state).free > self.jar.stat().st_size * 3 + self.c.get('minimum_free_bytes', 1 << 30))

    def wait_ready(self, sha, site, instance):
        deadline = time.monotonic() + self.c.get('health_timeout_seconds', 180)
        while True:
            try:
                self.health(site, instance)
                require(self.runtime_sha() == sha)
                return
            except Exception:
                if time.monotonic() >= deadline:
                    raise Rejected() from None
                time.sleep(1)

    def backup(self, directory):
        backup = directory / 'database.dump'
        with open(backup, 'xb') as out:
            os.chmod(backup, 0o600)
            self.command(['exec', '-T', 'postgres', 'sh', '-c',
                          'exec pg_dump -Fc --username="$POSTGRES_USER" --dbname="$PG_DB"'], output=out)
            out.flush()
            os.fsync(out.fileno())
        require(backup.stat().st_size > 0)
        with open(backup, 'rb') as stream:
            self.command(['exec', '-T', 'postgres', 'pg_restore', '--list'], stdin=stream, output=subprocess.DEVNULL)
        # Listing a dump is a format check, not a substitute for the operator's restore rehearsal.
        directory_sync(directory)

    def operate(self, action, job_id, sha, site, instance, old, stream):
        require(action in ('prepare', 'apply', 'status'))
        require(str(uuid.UUID(job_id)) == job_id and str(uuid.UUID(instance)) == instance)
        require(re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', site))
        require(re.fullmatch(r'[0-9a-f]{64}', sha) and re.fullmatch(r'[0-9a-f]{64}', old))
        require(site == self.c['site_id'] and instance == self.c['instance_id'])
        receipt = dict(id=job_id, siteId=site, instanceId=instance, artifactSha256=sha)
        with open(self.state / 'agent.lock', 'a+b') as lock:
            os.chmod(lock.name, 0o600)
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            journal = self.state / (job_id + '.json')
            require(not journal.is_symlink())
            if journal.exists():
                previous = json.loads(journal.read_text())
                require(all(previous.get(key) == value for key, value in receipt.items()) and previous.get('fromSha256') == old)
                if action == 'status' or previous['state'] in ('SUCCEEDED', 'ROLLED_BACK', 'ROLLBACK_FAILED', 'FAILED'):
                    # Drain a duplicate upload without changing anything; it cannot spawn a second rollout.
                    if action == 'apply':
                        while stream.read(65536):
                            pass
                    return previous
                raise Rejected()
            if action == 'status':
                self.preflight(site, instance, old)
                return {**receipt, 'state': 'NOT_STARTED'}
            # Any interrupted remote update fences new jobs, even after the SSH process disappeared.
            for other in self.state.glob('*.json'):
                require(json.loads(other.read_text()).get('state') not in ('DEPLOYING', 'ROLLING_BACK', 'ROLLBACK_FAILED'))
            self.preflight(site, instance, old)
            if action == 'prepare':
                return {**receipt, 'state': 'PREPARED'}
            directory = self.state / job_id
            directory.mkdir(mode=0o700)
            candidate = directory / 'candidate.jar'
            old_mode = stat.S_IMODE(self.jar.stat().st_mode)
            previous_jar = directory / 'previous.jar'
            mutated = False
            receipt.update(fromSha256=old, state='FAILED', backupId=job_id)
            try:
                digest = hashlib.sha256()
                total = 0
                with open(candidate, 'xb') as out:
                    os.chmod(candidate, 0o600)
                    for block in iter(lambda: stream.read(65536), b''):
                        total += len(block)
                        require(total <= self.c.get('maximum_artifact_bytes', 512 << 20))
                        digest.update(block)
                        out.write(block)
                    out.flush()
                    os.fsync(out.fileno())
                require(digest.hexdigest() == sha and total > 0)
                shutil.copyfile(self.jar, previous_jar)
                os.chmod(previous_jar, 0o600)
                with open(previous_jar, 'rb') as saved:
                    os.fsync(saved.fileno())
                require(sha_file(previous_jar) == old)
                self.backup(directory)
                # Repeat the identity/version check immediately before the mutation.
                self.preflight(site, instance, old)
                receipt['state'] = 'DEPLOYING'
                atomic_json(journal, receipt)
                mutated = True
                self.install(candidate, old_mode)
                self.command(['up', '-d', '--no-deps', '--force-recreate', 'app'])
                self.wait_ready(sha, site, instance)
                receipt['state'] = 'SUCCEEDED'
            except Exception:
                if mutated:
                    receipt['state'] = 'ROLLING_BACK'
                    atomic_json(journal, receipt)
                    try:
                        require(sha_file(previous_jar) == old)
                        self.install(previous_jar, old_mode)
                        self.command(['up', '-d', '--no-deps', '--force-recreate', 'app'])
                        self.wait_ready(old, site, instance)
                        receipt['state'] = 'ROLLED_BACK'
                    except Exception:
                        receipt['state'] = 'ROLLBACK_FAILED'
            atomic_json(journal, receipt)
            return receipt

    def install(self, source, mode):
        # The bind-mounted file is replaced atomically, then app alone is recreated to bind the new inode.
        fd, temporary = tempfile.mkstemp(prefix='.app-', dir=self.jar.parent)
        try:
            metadata = self.jar.stat()
            os.fchown(fd, metadata.st_uid, metadata.st_gid)
            os.fchmod(fd, mode)
            with os.fdopen(fd, 'wb') as out, open(source, 'rb') as data:
                shutil.copyfileobj(data, out)
                out.flush()
                os.fsync(out.fileno())
            os.replace(temporary, self.jar)
            directory_sync(self.jar.parent)
        finally:
            Path(temporary).unlink(missing_ok=True)


def main():
    os.umask(0o077)
    try:
        require(len(sys.argv) == 8)
        config, action, job_id, sha, site, instance, old = sys.argv[1:]
        result = Agent(config).operate(action, job_id, sha, site, instance, old, sys.stdin.buffer)
        print(json.dumps(result, separators=(',', ':')))
        return 0
    except Exception:
        # Paths, endpoints, commands, dump contents and tokens never enter protocol errors.
        print('{"code":"REMOTE_DEPLOYMENT_REJECTED_OR_UNKNOWN"}')
        return 1


if __name__ == '__main__':
    sys.exit(main())
