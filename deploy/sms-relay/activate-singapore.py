#!/usr/bin/env python3
"""Activate the reviewed relay for the existing Singapore app, with health rollback."""
import contextlib
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

ROOT = Path('/opt/eatwhat-sites/16343d72-2687-4e01-a8b1-9d9efe881474')
COMPOSE = ROOT / 'deploy/compose.json'
NAME = 'eatwhat-site-16343d72-app-1'
OLD_SHA = '81481ad14077d78926613dc9efb52891df7c44bef376e4a573db0852a449598a'


def command(args):
    r = subprocess.run(args, capture_output=True, text=True, timeout=90)
    if r.returncode:
        raise RuntimeError('command failed: ' + ' '.join(args[:3]))
    return r.stdout


def ready():
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open('http://127.0.0.1:18090/actuator/health/readiness', timeout=3) as r:
            return r.status == 200 and json.load(r).get('status') == 'UP'
    except Exception:
        return False


def wait_ready():
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        if ready():
            return
        time.sleep(2)
    raise RuntimeError('business readiness timed out')


def atomic(path, content):
    st = path.stat()
    temp = path.with_name(path.name + '.sms-relay-pending')
    with temp.open('xb') as out:
        os.fchmod(out.fileno(), st.st_mode & 0o777)
        os.fchown(out.fileno(), st.st_uid, st.st_gid)
        out.write(content)
        out.flush()
        os.fsync(out.fileno())
    os.replace(temp, path)


def main():
    if os.geteuid() != 0:
        raise RuntimeError('root required')
    if not ready():
        raise RuntimeError('business was not ready before change')
    jar = ROOT / 'releases/current.jar'
    if hashlib.file_digest(jar.open('rb'), 'sha256').hexdigest() != OLD_SHA:
        raise RuntimeError('running release changed since preflight')
    data = json.loads(COMPOSE.read_bytes())
    extra = data['services']['app'].get('extra_hosts', {})
    if not isinstance(extra, dict) or 'dypnsapi.aliyuncs.com' in extra:
        raise RuntimeError('unexpected existing hosts override')
    compose = ['docker', 'compose', '--project-name', 'eatwhat-site-16343d72',
               '--env-file', str(ROOT / 'deploy/.env'), '-f', str(COMPOSE)]
    dependencies = {service: command(['docker', 'inspect', '--format', '{{.Id}}',
                                      'eatwhat-site-16343d72-' + service + '-1']).strip()
                    for service in ['postgres', 'redis']}
    with contextlib.ExitStack() as stack:
        state = Path('/opt/eatwhat-control/state')
        for name in ['deployment.lock', 'servers.lock']:
            path = state / name
            if path.exists():
                stream = stack.enter_context(path.open('r+'))
                fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        for pattern in ['deployment-*.json', 'server-job-*.json', 'server-cluster-job-*.json']:
            for path in state.glob(pattern):
                if json.loads(path.read_text()).get('state') in ['QUEUED', 'RUNNING', 'OUTCOME_UNKNOWN']:
                    raise RuntimeError('existing control operation needs attention')
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        backup = ROOT / 'backups' / ('sms-relay-' + stamp)
        backup.mkdir(mode=0o700, parents=True)
        original = COMPOSE.read_bytes()
        saved = backup / 'compose.before.json'
        saved.write_bytes(original)
        saved.chmod(0o600)
        extra['dypnsapi.aliyuncs.com'] = '23.149.108.7'
        data['services']['app']['extra_hosts'] = extra
        print(json.dumps({'stage': 'BACKUP_CREATED', 'backup': str(backup)}), flush=True)
        try:
            atomic(COMPOSE, (json.dumps(data, indent=2) + '\n').encode())
            command(compose + ['config', '--quiet'])
            print(json.dumps({'stage': 'RECREATING_APP_ONLY'}), flush=True)
            command(compose + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'app'])
            wait_ready()
            after = json.loads(command(['docker', 'inspect', NAME]))[0]
            if 'dypnsapi.aliyuncs.com:23.149.108.7' not in after['HostConfig']['ExtraHosts']:
                raise RuntimeError('runtime hosts override missing')
            for service, before_id in dependencies.items():
                if command(['docker', 'inspect', '--format', '{{.Id}}',
                            'eatwhat-site-16343d72-' + service + '-1']).strip() != before_id:
                    raise RuntimeError('dependency container unexpectedly changed')
            actual = command(['docker', 'exec', NAME, 'sha256sum', '/app/app.jar']).split()[0]
            if actual != OLD_SHA:
                raise RuntimeError('runtime JAR unexpectedly changed')
        except Exception:
            atomic(COMPOSE, original)
            command(compose + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'app'])
            wait_ready()
            print(json.dumps({'stage': 'ROLLED_BACK', 'backup': str(backup)}), flush=True)
            raise
        report = {'stage': 'READY', 'backup': str(backup), 'runtimeJarSha256': actual,
                  'readiness': 'UP', 'databaseRedisContainersUnchanged': True,
                  'hostsOverride': {'dypnsapi.aliyuncs.com': '23.149.108.7'}}
        (backup / 'activation.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
