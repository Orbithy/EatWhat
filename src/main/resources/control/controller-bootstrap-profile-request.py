"""Pinned-SSH bridge to an already accepted controller profile operation.

No remote URL, file contents, approval or token is supplied by HTTP callers.
This program makes one loopback POST only, with no redirect/proxy/retry. Its
original bytes and flat token reference belong to the encrypted acceptance.
"""
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import pwd
import re
import stat
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
import zipfile

CONTROL = Path('/opt/eatwhat-control')
PROC = Path('/proc')
ROOT_UID = 0
PROTOCOL = 'eatwhat-controller-profile-publication-v1'
ENDPOINT = 'http://127.0.0.1:18081/internal/control/v1/controller-profiles/'
MARKER = 'META-INF/eatwhat-controller-profile-receiver-v1'


class Refusal(Exception):
    pass


def require(ok):
    if not ok:
        raise Refusal('BOOTSTRAP_PROFILE_REQUEST_UNAVAILABLE')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def decode(raw):
    def pairs(rows):
        value = {}
        for key, item in rows:
            require(key not in value)
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: require(False))


def validate(data):
    require(isinstance(data, dict) and set(data) == {'id', 'role', 'binding', 'applicationSha256', 'artifactSha256', 'tokenFile', 'sourceProcess'})
    require(isinstance(data['id'], str) and str(uuid.UUID(data['id'])) == data['id'] and uuid.UUID(data['id']).int != 0)
    require(data['role'] in ['SOURCE', 'TARGET'] and isinstance(data['binding'], str) and re.fullmatch('[A-Za-z0-9+/]{43}=', data['binding']))
    for key in ['applicationSha256', 'artifactSha256']:
        require(isinstance(data[key], str) and re.fullmatch('[a-f0-9]{64}', data[key]))
    require(isinstance(data['tokenFile'], str))
    path = Path(data['tokenFile'])
    require(path.parent == CONTROL / 'config' and str(path) == data['tokenFile'] and re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', path.name))
    proof = data['sourceProcess']
    if data['role'] == 'SOURCE':
        require(isinstance(proof, dict) and set(proof) == {'pid', 'startTicks', 'argvSha256', 'environmentSha256'})
        require(type(proof['pid']) is int and proof['pid'] > 1 and isinstance(proof['startTicks'], str) and re.fullmatch('[0-9]+', proof['startTicks']))
        for key in ['argvSha256', 'environmentSha256']:
            require(isinstance(proof[key], str) and re.fullmatch('[a-f0-9]{64}', proof[key]))
    else:
        require(proof is None)
    require(len(canonical(data)) <= 8192)
    return data


def private(path, maximum, uid):
    require(all(not parent.is_symlink() for parent in [path, *path.parents]))
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as source:
        info = os.fstat(source.fileno())
        require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600 and info.st_uid in [ROOT_UID, uid] and 0 < info.st_size <= maximum)
        raw = source.read(maximum + 1)
    require(len(raw) <= maximum)
    return raw


def command(args):
    result = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=8,
                            env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8'})
    require(result.returncode == 0 and len(result.stdout) <= 65536)
    return result.stdout


def loopback_listener(value):
    # Java may expose a socket bound to 127.0.0.1 as IPv4-mapped IPv6 in ss.
    # Accept only that same IPv4 address/port, never ::1, wildcard or another IP.
    if not isinstance(value, str) or '%' in value:
        return False
    if value == '127.0.0.1:18081':
        return True
    try:
        if not value.startswith('[') or not value.endswith(']:18081'):
            return False
        return ipaddress.IPv6Address(value[1:-7]).ipv4_mapped == ipaddress.IPv4Address('127.0.0.1')
    except (ValueError, TypeError):
        return False


def process(data, uid):
    pid = command(['systemctl', 'show', 'eatwhat-control.service', '-p', 'MainPID', '--value']).decode().strip()
    require(pid.isascii() and pid.isdigit() and int(pid) > 1)
    base = PROC / pid
    require(base.stat().st_uid == uid and (base / 'exe').resolve() == Path('/usr/bin/java').resolve())
    raw = (base / 'cmdline').read_bytes().split(b'\0')
    require(raw[-1] == b'')
    argv = [value.decode() for value in raw[:-1]]
    require(argv[0] == '/usr/bin/java' and argv.count('-jar') == 1)
    at = argv.index('-jar')
    require(all(re.fullmatch('-Xm[sx][1-9][0-9]*[mMgG]', value) for value in argv[1:at]))
    require(argv[at + 1:] == [str(CONTROL / 'acceptance' / (data['artifactSha256'] + '.jar')),
                            '--spring.config.location=file:' + str(CONTROL / 'config/application.properties'),
                            '--spring.config.additional-location=', '--spring.config.import='])
    environment = {}
    for row in (base / 'environ').read_bytes().split(b'\0'):
        if row:
            key, separator, value = row.partition(b'=')
            name = key.decode()
            require(separator and name not in environment)
            environment[name] = value.hex()
    require(not any(key.startswith(('SPRING_', 'APP_', 'LOADER_')) or key in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS'] for key in environment))
    ticks = (base / 'stat').read_text().rsplit(')', 1)[1].split()[19]
    rows = command(['ss', '-H', '-ltnp', 'sport = :18081']).decode().splitlines()
    require(len(rows) == 1 and len(rows[0].split()) >= 6 and loopback_listener(rows[0].split()[3])
            and re.search(r'\bpid=' + pid + r',', rows[0]))
    result = {'pid': int(pid), 'startTicks': ticks, 'argvSha256': digest(canonical(argv)), 'environmentSha256': digest(canonical(environment))}
    if data['role'] == 'SOURCE':
        require(result == data['sourceProcess'])
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def request(data, action):
    validate(data)
    require(action in ['apply', 'status','register','register-status','join','join-status','join-reconcile'] and os.geteuid() == ROOT_UID and sys.platform == 'linux')
    if action.startswith(('register','join')):require(data['role']=='SOURCE')
    uid = pwd.getpwnam('eatwhat-control').pw_uid
    before = process(data, uid)
    require(digest(private(CONTROL / 'config/application.properties', 262144, uid)) == data['applicationSha256'])
    artifact = private(CONTROL / 'acceptance' / (data['artifactSha256'] + '.jar'), 256 * 1024 * 1024, uid)
    require(digest(artifact) == data['artifactSha256'])
    import io
    with zipfile.ZipFile(io.BytesIO(artifact)) as jar:
        require(jar.namelist().count(MARKER) == 1 and jar.read(MARKER) == b'eatwhat-controller-profile-receiver-v1\n')
        capability='META-INF/eatwhat-controller-profile-services-successor-v1'
        require(jar.namelist().count(capability)==1 and jar.read(capability)==b'eatwhat-controller-profile-services-successor-v1\n'
                and 'BOOT-INF/classes/you/v50to/eatwhat/control/ControlBootstrapProfileServices.class' in jar.namelist())
        membership='META-INF/eatwhat-controller-bootstrap-membership-v1'
        require(jar.namelist().count(membership)==1 and jar.read(membership)==b'eatwhat-controller-bootstrap-membership-v1\n'
                and all('BOOT-INF/classes/you/v50to/eatwhat/control/'+name+'.class' in jar.namelist()
                        for name in ['ControlBootstrapMembership','ControlBootstrapMembershipNodeController']))
        require(jar.read('META-INF/eatwhat-controller-bootstrap-membership-v2')==b'eatwhat-controller-bootstrap-membership-v2\n'
                and 'BOOT-INF/classes/you/v50to/eatwhat/cluster/ClusterMembershipCoordinator$Boundary.class' in jar.namelist())
    token = private(Path(data['tokenFile']), 256, uid).decode('ascii').strip()
    require(re.fullmatch('[A-Za-z0-9_-]{43,128}', token))
    frame = {key: data[key] for key in ['id', 'role', 'binding']}
    outgoing = urllib.request.Request(ENDPOINT + action, data=canonical(frame), method='POST',
                                     headers={'Content-Type': 'application/json', 'X-Control-Node-Token': token})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(outgoing, timeout=30) as response:
            require(response.status == 200 and response.headers.get_content_type() == 'application/json')
            raw = response.read(8193)
    except urllib.error.HTTPError as failure:
        failure.close()
        raise Refusal('BOOTSTRAP_PROFILE_REQUEST_UNAVAILABLE') from None
    require(len(raw) <= 8192)
    result = decode(raw)
    require(isinstance(result, dict) and set(result) == {'protocol', 'bootstrapId', 'role', 'binding', 'planBinding', 'controllerId', 'controllerName', 'applicationSha256', 'state', 'observedBinding', 'joinAuthorized', 'signature'})
    require(result['protocol'] == PROTOCOL and result['bootstrapId'] == data['id'] and result['role'] == data['role'] and result['binding'] == data['binding']
            and result['applicationSha256'] == data['applicationSha256'] and result['joinAuthorized'] is False
            and result['state'] in (['REGISTERED','OUTCOME_UNKNOWN'] if action.startswith('register') else ['PREPARING','COMMITTED','INSTALLED','ACTIVE','OUTCOME_UNKNOWN'] if action.startswith('join') else ['RUNTIME_RELOADED', 'OUTCOME_UNKNOWN']))
    require(process(data, uid) == before)
    require(digest(private(CONTROL / 'config/application.properties', 262144, uid)) == data['applicationSha256'])
    # The manager verifies the original role process nonce and receipt HMAC.
    return result


if __name__ == '__main__':
    try:
        require(len(sys.argv) == 2)
        raw = sys.stdin.buffer.read(8193)
        require(len(raw) <= 8192)
        print(canonical(request(decode(raw), sys.argv[1])).decode())
    except Exception:
        # Never echo HTTP errors, response bodies, private paths or tokens.
        print(json.dumps({'protocol': PROTOCOL, 'code': 'BOOTSTRAP_PROFILE_REQUEST_UNAVAILABLE'}))
        sys.exit(1)
