# Singapore Aliyun SMS relay

Deployed on 2026-10-23. This is an infrastructure change to the existing
Singapore business deployment; the business JAR and SMS credentials were not
changed.

## Traffic path

The Singapore business container resolves only `dypnsapi.aliyuncs.com` to
`23.149.108.7`, through its Compose `extra_hosts` entry. The Singapore host's
owned `ip eatwhat_sms_relay` nftables table maps container traffic to that
address on TCP 443 to TCP 39857. It matches the business Docker network bridge,
its IPv4 subnet, the fixed destination address and destination port.

On `23.149.108.7`, `eatwhat-sms-relay.service` forwards opaque TCP streams to
`dypnsapi.aliyuncs.com:443`. It accepts only source `51.79.204.39`. It uses the
relay server's normal DNS, so the upstream IP addresses are not pinned. TLS,
SNI, HTTP Host and API signing remain end to end between the application and
Aliyun. No SMS credentials or payloads are installed or logged on the relay.

The relay listens on an independent port because the host's existing 443
listener belongs to another service. Neither existing HTTPS ingress was edited.

## Installed assets

On the relay host:

- `/opt/eatwhat-sms-relay/relay.py`
- `/etc/systemd/system/eatwhat-sms-relay.service`

On Singapore:

- `/opt/eatwhat-sms-relay/apply-route.py`
- `/etc/systemd/system/eatwhat-sms-route.service`
- Application mapping in
  `/opt/eatwhat-sites/16343d72-2687-4e01-a8b1-9d9efe881474/deploy/compose.json`

Both services are enabled at boot. The relay runs as a dynamic, unprivileged
systemd user, with bounded connections, timeouts and memory. The route service
owns one dedicated nftables table and discovers the current Docker bridge at
startup. It does not flush or replace other tables.

## Validation

`python3 -m unittest discover -s deploy/sms-relay -p 'test_*.py' -v`
checks concurrent binary streams, request half-close with a delayed response,
source rejection before opening an upstream connection, and rejection of other
CLI destinations.

Production validation confirmed:

- Relay service restart and enabled state; non-Singapore source rejected.
- App container resolves the SMS domain to the relay without curl overrides.
- Five HTTPS probes completed in 0.324–0.496 s, with certificate validation.
- Business readiness `UP`, original JAR hash unchanged, database and Redis
  container IDs unchanged.
- Exactly one authorized SMS request returned HTTP 200 / business code 0 in
  2.052 s. The owned route counter increased by one connection. The user
  confirmed receipt. No SMS retry was issued.

The single-send marker and activation report are stored privately on Singapore
inside the backup directory below. They contain no phone number or OTP.

## Operations and rollback

On the relay host, inspect `systemctl status eatwhat-sms-relay` and its journal.
Application log entries contain only readiness and connection outcome enums.
On Singapore, inspect `systemctl status eatwhat-sms-route` and
`nft list table ip eatwhat_sms_relay`.

The activation backup is:

`/opt/eatwhat-sites/16343d72-2687-4e01-a8b1-9d9efe881474/backups/sms-relay-20261023T124100Z/`

For immediate rollback, first confirm no later Compose edits or deployment are
in progress. Restore `compose.before.json` to `deploy/compose.json` and recreate
only the `app` service using that deployment's original project name and `.env`:

```sh
cd /opt/eatwhat-sites/16343d72-2687-4e01-a8b1-9d9efe881474/deploy
cp ../backups/sms-relay-20261023T124100Z/compose.before.json compose.json
docker compose --project-name eatwhat-site-16343d72 --env-file .env -f compose.json up -d --no-deps --no-build --pull never app
curl --fail http://127.0.0.1:18090/actuator/health/readiness
systemctl disable --now eatwhat-sms-route.service
```

If other Compose changes have since been made, remove only the exact
`dypnsapi.aliyuncs.com` mapping instead of replacing the complete file. Then
perform the same app recreation and readiness check. Stop the Hong Kong relay
with `systemctl disable --now eatwhat-sms-relay.service` after Singapore has
reverted. No database restoration is required.

When recreating the Docker network rather than only the app container, restart
`eatwhat-sms-route.service` to refresh its bridge name and subnet. Ordinary app
releases using the existing Compose file preserve the mapping; regenerating
that file must retain the app's SMS `extra_hosts` entry.

This relay currently has one upstream host and no automatic direct fallback.
If the relay host is unavailable, SMS sending fails until the relay recovers
or this mapping is rolled back. The small acceptance sample does not establish
long-term availability.
