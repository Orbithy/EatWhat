"""Durable runtime/key phase for the composite controller installer.

Not exposed by HTTP yet. Only reviewed embedded code is dispatched over pinned
SSH. No controller, WireGuard peer, state mount or business is started here.
An accepted or interrupted phase is only observed, never silently re-dispatched.
"""
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shlex
import stat
import subprocess
import sys
import time
import types
import uuid

PREFLIGHT_SOURCE = '__EATWHAT_CONTROLLER_PREFLIGHT_BASE64__'
_PREFLIGHT = None
PROTOCOL = 'eatwhat-controller-bootstrap-runtime-v1'
ROOT_UID = 0
BASE = Path('/var/lib/eatwhat-controller-bootstrap')
POLICY = Path('/usr/sbin/policy-rc.d')
SYSTEM = Path('/etc/systemd/system')
RPC_UNITS = ['rpcbind.service', 'rpcbind.socket', 'rpc-statd.service', 'rpc-statd-notify.service']
NFS_UNITS = ['nfs-server.service', 'nfs-kernel-server.service', 'nfs-mountd.service']
NFT_UNITS = ['nftables.service']
STAGE = 'PREPARE_RUNTIME'
MAX_INPUT = 524288


class Refusal(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def require(value, code='BOOTSTRAP_RUNTIME_PRECONDITION_FAILED'):
    if not value:
        raise Refusal(code)


def preflight():
    global _PREFLIGHT
    if _PREFLIGHT is None:
        module = types.ModuleType('reviewed_controller_preflight')
        exec(compile(base64.b64decode(PREFLIGHT_SOURCE), '<reviewed-preflight>', 'exec'), module.__dict__)
        _PREFLIGHT = module
    return _PREFLIGHT


def canonical(data):
    return json.dumps(data, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def directory(path):
    for parent in [path, *path.parents]:
        require(not parent.is_symlink(), 'BOOTSTRAP_RUNTIME_UNSAFE_PATH')
    require(path.is_dir() and path.stat().st_uid == ROOT_UID
            and stat.S_IMODE(path.stat().st_mode) == 0o700, 'BOOTSTRAP_RUNTIME_UNSAFE_PATH')


def private(path, maximum=MAX_INPUT):
    directory(path.parent)
    require(not path.is_symlink() and path.is_file(), 'BOOTSTRAP_RUNTIME_UNSAFE_PATH')
    info = path.stat()
    require(info.st_uid == ROOT_UID and stat.S_IMODE(info.st_mode) == 0o600 and info.st_size <= maximum,
            'BOOTSTRAP_RUNTIME_UNSAFE_PATH')
    return path.read_bytes()


def atomic(path, data):
    directory(path.parent)
    if path.exists() or path.is_symlink():
        private(path)
    raw = data if isinstance(data, bytes) else canonical(data)
    temp = path.with_name('.write-' + str(uuid.uuid4()))
    try:
        with temp.open('xb') as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
        fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    finally:
        temp.unlink(missing_ok=True)


def validate(data, agent=False):
    required = {'request', 'expectedProof', 'approvalDigest', 'agentSha256'}
    require(isinstance(data, dict) and set(data) == required | ({'agent'} if agent else set()))
    preflight().validate(data['request'])
    for key in ['expectedProof', 'approvalDigest', 'agentSha256']:
        require(isinstance(data[key], str) and re.fullmatch(r'[a-f0-9]{64}', data[key]))
    if agent:
        require(isinstance(data['agent'], str) and len(data['agent'].encode()) <= MAX_INPUT
                and digest(data['agent'].encode()) == data['agentSha256'])
    return data


def binding(data):
    # Bind the exact approval, both host identities, role, addresses and code.
    return digest(canonical({key: value for key, value in data.items() if key != 'agent'}))


def task(data):
    return BASE / data['request']['id']


def fields(data):
    request = data['request']
    return {'protocol': PROTOCOL, 'id': request['id'], 'role': request['role'],
            'sourceServerId': request['sourceServerId'], 'targetServerId': request['targetServerId'],
            'stage': STAGE, 'approvalDigest': data['approvalDigest'], 'binding': binding(data)}


def run(args, stdin=None, timeout=30):
    # A separate process group bounds apt and its maintainer-script descendants.
    capture = args[0] not in ['apt-get', 'systemd-run']
    proc = subprocess.Popen(args, stdin=subprocess.PIPE if stdin is not None else subprocess.DEVNULL,
                            stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, start_new_session=True,
                            env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8',
                                 'DEBIAN_FRONTEND': 'noninteractive', 'NEEDRESTART_MODE': 'l'})
    try:
        output, _ = proc.communicate(stdin, timeout=timeout)
        output = output or b''
        require(len(output) <= 262144 and proc.returncode == 0, 'BOOTSTRAP_RUNTIME_COMMAND_FAILED')
        return output
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
            proc.communicate(timeout=5)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            proc.wait(timeout=10)
        raise Refusal('BOOTSTRAP_RUNTIME_COMMAND_TIMED_OUT')
    finally:
        if proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            proc.wait(timeout=10)


def unit(data):
    return 'eatwhat-controller-runtime-' + data['request']['id']


def read_receipt(data):
    path = task(data)
    if not path.exists() and not path.is_symlink():
        return None
    directory(path)
    receipt = path / 'runtime-receipt.json'
    if not receipt.exists() and not receipt.is_symlink():
        return None
    result = json.loads(private(receipt))
    require(all(result.get(key) == value for key, value in fields(data).items()),
            'BOOTSTRAP_RUNTIME_BINDING_CHANGED')
    return result


def status(data):
    validate(data)
    if not BASE.exists() and not BASE.is_symlink():
        return {**fields(data), 'state': 'NOT_STARTED', 'effectsStarted': False}
    directory(BASE)
    reservation = BASE / 'reservation.json'
    if reservation.exists() or reservation.is_symlink():
        saved = json.loads(private(reservation))
        require(saved.get('id') == data['request']['id'] and saved.get('binding') == binding(data),
                'BOOTSTRAP_RUNTIME_HOST_RESERVED')
    else:
        require(all(path.name == 'host.lock' for path in BASE.iterdir()),
                'BOOTSTRAP_RUNTIME_RESERVATION_MISSING')
    result = read_receipt(data)
    if result is None:
        return {**fields(data), 'state': 'OUTCOME_UNKNOWN' if reservation.exists() else 'NOT_STARTED',
                'effectsStarted': False, 'code': 'BOOTSTRAP_RUNTIME_ACCEPTANCE_UNCONFIRMED'}
    if result['state'] in ['ACCEPTED', 'RUNNING'] and time.time() - result['acceptedAt'] >= 15:
        try:
            active = run(['systemctl', 'show', '--property=ActiveState', '--value', unit(data)], timeout=5).strip()
            if active in [b'inactive', b'failed']:
                # Observation never repairs a receipt, invokes apt or repeats a dispatch.
                if result.get('effectsStarted') is True:
                    try:
                        return derive_ready(data, result)
                    except Exception:
                        pass
                return {**result, 'state': 'OUTCOME_UNKNOWN', 'code': 'BOOTSTRAP_RUNTIME_RECONCILIATION_REQUIRED'}
            require(active in [b'active', b'activating', b'deactivating', b'reloading'],
                    'BOOTSTRAP_RUNTIME_WORKER_UNOBSERVABLE')
        except Exception:
            return {**result, 'state': 'OUTCOME_UNKNOWN', 'code': 'BOOTSTRAP_RUNTIME_WORKER_UNOBSERVABLE'}
    return result


def derive_ready(data, receipt):
    """Prove a late completed phase without rewriting its original receipt."""
    with host_lock():
        original = json.loads(private(task(data) / 'preflight.json'))
        require(digest(canonical({'request': data['request'], 'facts': original['facts']})) == data['expectedProof'])
        require(not POLICY.exists() and not POLICY.is_symlink())
        verify_service_guards(data)
        current = preflight().inspect(data['request'])
        before = {key: value for key, value in original['facts'].items() if key != 'packages'}
        after = {key: value for key, value in current['facts'].items() if key != 'packages'}
        require(before == after)
        installed = current['facts']['packages']
        require(set(installed) == set(preflight().PACKAGES[data['request']['role']]) and all(installed.values()))
        public = before['network']['existingPublicKey']
        existing = data['request']['role'] == 'SOURCE' and public is not None
        key_proof = json.loads(private(task(data) / 'runtime-key-proof.json'))
        require(set(key_proof) == {'publicKey', 'keySource', 'privateKeySha256',
                                   'installedBaselineSha256', 'aptPreferencesSha256'})
        baseline_path = task(data) / 'installed-baseline.json'
        if baseline_path.exists() or baseline_path.is_symlink():
            raw = private(baseline_path)
            require(digest(raw) == key_proof['installedBaselineSha256'])
            require(digest(private(task(data) / 'apt-preferences')) == key_proof['aptPreferencesSha256'])
            baseline = json.loads(raw)
            require(isinstance(baseline, dict) and baseline)
            current_versions = installed_versions()
            require(all(current_versions.get(name) == version for name, version in baseline.items()))
        else:
            require(all(original['facts']['packages'].values())
                    and key_proof['installedBaselineSha256'] is None
                    and key_proof['aptPreferencesSha256'] is None)
        require(key_proof['keySource'] == ('EXISTING_SOURCE_INTERFACE' if existing else 'LOCAL_JOB_KEY'))
        if not existing:
            secret = private(task(data) / 'wireguard.key', 64)
            require(re.fullmatch(rb'[A-Za-z0-9+/]{43}=\n?', secret))
            require(digest(secret) == key_proof['privateKeySha256'])
            public = run(['wg', 'pubkey'], stdin=secret, timeout=5).decode().strip()
        else:
            require(key_proof['privateKeySha256'] is None)
        require(re.fullmatch(r'[A-Za-z0-9+/]{43}=', public) and key_proof['publicKey'] == public)
        return {**receipt, 'state': 'RUNTIME_READY', 'packages': installed, 'publicKey': public,
                'keySource': 'EXISTING_SOURCE_INTERFACE' if existing else 'LOCAL_JOB_KEY',
                'controllerStarted': False, 'businessInstalled': False, 'derivedFromRuntime': True}


def host_lock(create=False):
    if create:
        if not BASE.exists():
            BASE.mkdir(mode=0o700)
        directory(BASE)
    else:
        directory(BASE)
    path = BASE / 'host.lock'
    if not path.exists() and create:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
        os.close(fd)
    private(path)
    stream = path.open('r+')
    try:
        fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except Exception:
        stream.close()
        raise Refusal('BOOTSTRAP_RUNTIME_HOST_BUSY')
    return stream


def dispatch(data):
    validate(data, agent=True)
    header = {key: value for key, value in data.items() if key != 'agent'}
    prior = status(header)
    if prior['state'] != 'NOT_STARTED':
        return prior
    # This check precedes directory/reservation/key creation and every apt call.
    require(preflight().inspect(data['request'])['proof'] == data['expectedProof'],
            'BOOTSTRAP_RUNTIME_PLAN_CHANGED')
    with host_lock(create=True):
        prior = status(header)
        if prior['state'] != 'NOT_STARTED':
            return prior
        atomic(BASE / 'reservation.json', {'id': data['request']['id'], 'binding': binding(data)})
        path = task(data)
        require(not path.exists() and not path.is_symlink(), 'BOOTSTRAP_RUNTIME_TASK_RETAINED')
        path.mkdir(mode=0o700)
        atomic(path / 'runtime-input.json', header)
        atomic(path / 'runtime-agent.py', data['agent'].encode())
        receipt = {**fields(data), 'state': 'ACCEPTED', 'acceptedAt': time.time(), 'effectsStarted': False}
        atomic(path / 'runtime-receipt.json', receipt)
    # The reservation/ACCEPTED receipt prevents another UUID entering. Release
    # the host lock before starting a worker that must acquire that same lock.
    try:
        run(['systemd-run', '--quiet', '--no-block', '--unit=' + unit(data), '--property=Type=exec',
             '--property=UMask=0077', '--property=RuntimeMaxSec=1200', '--property=StandardOutput=null',
             '--property=StandardError=null', '/usr/bin/python3', str(path / 'runtime-agent.py'),
             'run', data['request']['id']], timeout=15)
    except Exception:
        # systemd may have accepted the unit before SSH/process output was lost.
        # The original durable ACCEPTED receipt stays available for observation.
        return {**receipt, 'state': 'OUTCOME_UNKNOWN', 'code': 'BOOTSTRAP_RUNTIME_DISPATCH_UNCONFIRMED'}
    return receipt


def policy_bytes(data):
    return ('#!/bin/sh\n# eatwhat-controller-runtime-' + data['request']['id'] + '\nexit 101\n').encode()


def prepare_service_guards(data, missing):
    units = []
    if {'nfs-common', 'nfs-kernel-server'}.intersection(missing):
        units += RPC_UNITS + (NFS_UNITS if data['request']['role'] == 'SOURCE' else [])
    if 'nftables' in missing:
        units += NFT_UNITS
    if not units:
        return
    # Only absent packages/units get guards. Never change a pre-existing NFS,
    # RPC or nftables policy. Validate the whole set before creating anything.
    for name in units:
        conflict = 'BOOTSTRAP_RUNTIME_EXISTING_FIREWALL_SERVICE' if name in NFT_UNITS else 'BOOTSTRAP_RUNTIME_EXISTING_NFS_SERVICE'
        load = run(['systemctl', 'show', '--property=LoadState', '--value', name], timeout=5).strip()
        require(load == b'not-found', conflict)
        path = SYSTEM / (name + '.d')
        require(not path.exists() and not path.is_symlink(), conflict)
    records = []
    for name in units:
        parent = SYSTEM / (name + '.d')
        require(all(not p.is_symlink() for p in [SYSTEM, *SYSTEM.parents]), 'BOOTSTRAP_RUNTIME_UNSAFE_PATH')
        parent.mkdir(mode=0o755)
        # The worker has UMask=0077. Only this new inode is adjusted; never
        # chmod an administrator-owned drop-in directory.
        os.chmod(parent, 0o755)
        # nftables' stock configuration may flush the entire ruleset, including
        # rules predating this installation. Our private state guard uses its
        # own unit/table; it never opens the stock service's condition.
        marker = task(data) / ('stock-firewall-not-approved' if name in NFT_UNITS else
                              'legacy-rpc-not-approved' if name in RPC_UNITS else 'shared-state-services-ready')
        content = ('# EatWhat bootstrap ' + data['request']['id'] + '\n[Unit]\nConditionPathExists=' + str(marker) + '\n').encode()
        path = parent / '90-eatwhat-bootstrap.conf'
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o644)
        with os.fdopen(fd, 'wb') as stream:
            os.fchmod(stream.fileno(), 0o644)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        records.append({'unit': name, 'path': str(path), 'sha256': digest(content)})
    atomic(task(data) / 'service-guards.json', records)
    # Maintainer scripts may enable a unit for next boot even when policy-rc.d
    # denies its immediate start. Persistent conditions close that reboot gap.
    run(['systemctl', 'daemon-reload'], timeout=15)


def verify_service_guards(data, check_inactive=False):
    path = task(data) / 'service-guards.json'
    if not path.exists() and not path.is_symlink():
        return
    records = json.loads(private(path))
    require(isinstance(records, list) and 0 < len(records) <= len(RPC_UNITS) + len(NFS_UNITS) + len(NFT_UNITS))
    require(all(isinstance(record, dict) and set(record) == {'unit', 'path', 'sha256'} for record in records)
            and len({record['unit'] for record in records}) == len(records), 'BOOTSTRAP_RUNTIME_SERVICE_GUARD_CHANGED')
    unexpected = False
    for record in records:
        require(record['unit'] in RPC_UNITS + NFS_UNITS + NFT_UNITS)
        marker = task(data) / ('stock-firewall-not-approved' if record['unit'] in NFT_UNITS else
                              'legacy-rpc-not-approved' if record['unit'] in RPC_UNITS else 'shared-state-services-ready')
        content = ('# EatWhat bootstrap ' + data['request']['id'] + '\n[Unit]\nConditionPathExists=' + str(marker) + '\n').encode()
        dest = SYSTEM / (record['unit'] + '.d') / '90-eatwhat-bootstrap.conf'
        require(str(dest) == record['path'] and all(not p.is_symlink() for p in [dest, *dest.parents])
                and dest.is_file() and dest.stat().st_uid == ROOT_UID and stat.S_IMODE(dest.stat().st_mode) == 0o644
                and dest.read_bytes() == content and digest(content) == record['sha256'],
                'BOOTSTRAP_RUNTIME_SERVICE_GUARD_CHANGED')
        if record['unit'] in RPC_UNITS + NFT_UNITS:
            require(not marker.exists() and not marker.is_symlink(), 'BOOTSTRAP_RUNTIME_SERVICE_GUARD_OPENED')
        if check_inactive:
            state = run(['systemctl', 'show', '--property=ActiveState', '--value', record['unit']], timeout=5).strip()
            if state in [b'active', b'activating', b'reloading']:
                # The load state was not-found before our package installation;
                # these are precisely the newly introduced units, not a foreign service.
                run(['systemctl', 'stop', record['unit']], timeout=20)
                unexpected = True
            else:
                require(state in [b'inactive', b'failed'], 'BOOTSTRAP_RUNTIME_SERVICE_UNOBSERVABLE')
    require(not unexpected, 'BOOTSTRAP_RUNTIME_UNEXPECTED_SERVICE_START')


def install_missing(data):
    before = preflight().packages(data['request']['role'])
    missing = [name for name, version in before.items() if version is None]
    if not missing:
        return before
    # Unknown administrator policy is not overwritten, adopted or removed.
    require(not POLICY.exists() and not POLICY.is_symlink(), 'BOOTSTRAP_RUNTIME_PACKAGE_POLICY_CONFLICT')
    preferences, baseline = freeze_installed(data)
    prepare_service_guards(data, missing)
    guard = policy_bytes(data)
    fd = os.open(POLICY, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o755)
    with os.fdopen(fd, 'wb') as stream:
        os.fchmod(stream.fileno(), 0o755)
        stream.write(guard)
        stream.flush()
        os.fsync(stream.fileno())
    # On failure the owned guard is intentionally retained. The composite job
    # remains blocked; another UUID must not bypass an interrupted apt phase.
    run(['apt-get', '-o', 'DPkg::Lock::Timeout=45', 'update'], timeout=240)
    run(['apt-get', '-o', 'DPkg::Lock::Timeout=45', 'install', '-y', '--no-install-recommends',
         '--no-upgrade', '--no-remove', '-o', 'APT::Get::allow-downgrades=false',
         '-o', 'Dir::Etc::preferences=' + str(preferences), '--', *missing], timeout=600)
    require(not POLICY.is_symlink() and POLICY.is_file() and POLICY.stat().st_uid == ROOT_UID
            and POLICY.read_bytes() == guard, 'BOOTSTRAP_RUNTIME_PACKAGE_POLICY_CHANGED')
    after = preflight().packages(data['request']['role'])
    require(all(after.get(name) for name in before), 'BOOTSTRAP_RUNTIME_PACKAGES_INCOMPLETE')
    current = installed_versions()
    require(all(current.get(name) == version for name, version in baseline.items()),
            'BOOTSTRAP_RUNTIME_EXISTING_PACKAGES_CHANGED')
    verify_service_guards(data, check_inactive=True)
    POLICY.unlink()
    return after


def installed_versions():
    raw = run(['dpkg-query', '-W', '-f=${binary:Package}\t${Version}\t${db:Status-Status}\n'], timeout=15)
    result = {}
    for line in raw.decode().splitlines():
        parts = line.split('\t')
        require(len(parts) == 3, 'BOOTSTRAP_RUNTIME_PACKAGE_INVENTORY_INVALID')
        name, version, state = parts
        if state != 'installed':
            continue
        require(re.fullmatch(r'[a-z0-9][a-z0-9+.-]{0,127}(?::[a-z0-9][a-z0-9-]{0,31})?', name)
                and re.fullmatch(r'[0-9][A-Za-z0-9.+:~_-]{0,255}', version)
                and name not in result, 'BOOTSTRAP_RUNTIME_PACKAGE_INVENTORY_INVALID')
        result[name] = version
    require(0 < len(result) <= 8192, 'BOOTSTRAP_RUNTIME_PACKAGE_INVENTORY_INVALID')
    return result


def freeze_installed(data):
    # --no-upgrade protects only explicit command-line packages. Dependencies
    # need an invocation-local pin too; never alter dpkg holds or host preferences.
    baseline = installed_versions()
    fields = shlex.split(run(['apt-config', 'shell', 'Preference', 'Dir::Etc::preferences/f'], timeout=5).decode())
    require(len(fields) == 1 and fields[0].startswith('Preference='),
            'BOOTSTRAP_RUNTIME_PACKAGE_PREFERENCES_UNAVAILABLE')
    original = Path(fields[0].split('=', 1)[1])
    require(original.is_absolute() and '..' not in original.parts,
            'BOOTSTRAP_RUNTIME_PACKAGE_PREFERENCES_UNAVAILABLE')
    existing = b''
    if original.exists() or original.is_symlink():
        require(all(not p.is_symlink() for p in [original, *original.parents])
                and original.is_file() and original.stat().st_uid == ROOT_UID
                and original.stat().st_size <= 262144,
                'BOOTSTRAP_RUNTIME_PACKAGE_PREFERENCES_UNAVAILABLE')
        existing = original.read_bytes()
    pins = ''.join('Package: ' + name + '\nPin: version ' + version + '\nPin-Priority: 1001\n\n'
                   'Package: ' + name + '\nPin: version *\nPin-Priority: -1\n\n'
                   for name, version in sorted(baseline.items())).encode()
    require(len(pins) + len(existing) <= MAX_INPUT, 'BOOTSTRAP_RUNTIME_PACKAGE_PREFERENCES_TOO_LARGE')
    preferences = task(data) / 'apt-preferences'
    atomic(preferences, pins + existing)
    atomic(task(data) / 'installed-baseline.json', baseline)
    # Existing preference fragments and repository/trust configuration remain
    # in force. Only installed versions gain an earlier specific prohibition.
    return preferences, baseline


def perform(id):
    require(str(uuid.UUID(id)) == id and uuid.UUID(id).int != 0)
    path = BASE / id
    directory(path)
    data = validate(json.loads(private(path / 'runtime-input.json')))
    require(data['request']['id'] == id and digest(private(path / 'runtime-agent.py')) == data['agentSha256'])
    with host_lock():
        receipt = read_receipt(data)
        require(receipt is not None and receipt['state'] == 'ACCEPTED', 'BOOTSTRAP_RUNTIME_ALREADY_DISPATCHED')
        reservation = json.loads(private(BASE / 'reservation.json'))
        require(reservation == {'id': id, 'binding': binding(data)}, 'BOOTSTRAP_RUNTIME_HOST_RESERVED')
        try:
            observed = preflight().inspect(data['request'])
            require(observed['proof'] == data['expectedProof'],
                    'BOOTSTRAP_RUNTIME_PLAN_CHANGED')
            atomic(path / 'preflight.json', observed)
            receipt.update(state='RUNNING', effectsStarted=True)
            atomic(path / 'runtime-receipt.json', receipt)
            installed = install_missing(data)
            public = observed.get('facts', {}).get('network', {}).get('existingPublicKey')
            existing = data['request']['role'] == 'SOURCE' and public is not None
            secret = None
            if not existing:
                secret = run(['wg', 'genkey'], timeout=5)
                require(re.fullmatch(rb'[A-Za-z0-9+/]{43}=\n?', secret), 'BOOTSTRAP_RUNTIME_KEY_GENERATION_FAILED')
                public = run(['wg', 'pubkey'], stdin=secret, timeout=5).decode().strip()
            require(re.fullmatch(r'[A-Za-z0-9+/]{43}=', public), 'BOOTSTRAP_RUNTIME_KEY_GENERATION_FAILED')
            atomic(path / 'runtime-key-proof.json', {'publicKey': public,
                   'keySource': 'EXISTING_SOURCE_INTERFACE' if existing else 'LOCAL_JOB_KEY',
                   'privateKeySha256': digest(secret) if secret is not None else None,
                   'installedBaselineSha256': digest(private(path / 'installed-baseline.json')) if (path / 'installed-baseline.json').exists() else None,
                   'aptPreferencesSha256': digest(private(path / 'apt-preferences')) if (path / 'apt-preferences').exists() else None})
            if not existing:
                require(not (path / 'wireguard.key').exists() and not (path / 'wireguard.key').is_symlink(),
                        'BOOTSTRAP_RUNTIME_KEY_RETAINED')
                atomic(path / 'wireguard.key', secret)
            receipt.update(state='RUNTIME_READY', packages=installed, publicKey=public, finishedAt=time.time(),
                           keySource='EXISTING_SOURCE_INTERFACE' if existing else 'LOCAL_JOB_KEY',
                           privateServiceGuardsRetained=(path / 'service-guards.json').exists(),
                           controllerStarted=False, businessInstalled=False)
        except Exception as failure:
            receipt.update(state='FAILED', code=failure.code if isinstance(failure, Refusal)
                           else 'BOOTSTRAP_RUNTIME_FAILED_PRESERVED', finishedAt=time.time())
        atomic(path / 'runtime-receipt.json', receipt)
        return receipt


if __name__ == '__main__':
    try:
        require(os.geteuid() == ROOT_UID and sys.platform == 'linux', 'BOOTSTRAP_RUNTIME_LINUX_ROOT_REQUIRED')
        if len(sys.argv) == 3 and sys.argv[1] == 'run':
            reply = perform(sys.argv[2])
        else:
            require(len(sys.argv) == 2 and sys.argv[1] in ['dispatch', 'status'])
            raw = sys.stdin.buffer.read(MAX_INPUT + 1)
            require(len(raw) <= MAX_INPUT, 'BOOTSTRAP_RUNTIME_INPUT_TOO_LARGE')
            data = json.loads(raw)
            reply = dispatch(data) if sys.argv[1] == 'dispatch' else status(data)
        print(json.dumps(reply))
    except Refusal as failure:
        uncertain = len(sys.argv) > 1 and sys.argv[1] in ['status', 'run']
        reply = {'protocol': PROTOCOL, 'state': 'OUTCOME_UNKNOWN' if uncertain else 'REJECTED', 'code': failure.code}
        if not uncertain:
            reply['effectsStarted'] = False
        print(json.dumps(reply))
        sys.exit(1)
    except Exception:
        print(json.dumps({'protocol': PROTOCOL, 'state': 'OUTCOME_UNKNOWN',
                          'code': 'BOOTSTRAP_RUNTIME_UNAVAILABLE'}))
        sys.exit(1)
