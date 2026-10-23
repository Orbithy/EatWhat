"""Read-only inventory for the from-zero controller+business installation plan.

This is the first phase of the composite installer, not its executor. It never
installs packages, generates credentials, creates directories, or starts a unit.
The manager must freeze its private proof before explicitly approving effects.
"""
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import platform
import pwd
import re
import shutil
import stat
import subprocess
import sys
import uuid

PROTOCOL = 'eatwhat-controller-bootstrap-preflight-v1'
CONTROL = Path('/opt/eatwhat-control')
SYSTEM = Path('/etc/systemd/system')
WIREGUARD = Path('/etc/wireguard')
PROC = Path('/proc')
OS_RELEASE = Path('/etc/os-release')
SYSTEMD_RUNTIME = Path('/run/systemd/system')
INTERFACE = 'ewctrl0'
PACKAGES = {
    'SOURCE': ['wireguard-tools', 'nfs-kernel-server', 'nftables', 'openssh-server'],
    'TARGET': ['openjdk-21-jre-headless', 'wireguard-tools', 'nfs-common', 'nftables', 'openssh-client'],
}


class Refusal(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def require(condition, code):
    if not condition:
        raise Refusal(code)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def command(args, allow_missing=False):
    result = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=8, check=False,
                            env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8'})
    require(len(result.stdout) <= 262144, 'BOOTSTRAP_INVENTORY_TOO_LARGE')
    if allow_missing and result.returncode == 1:
        return None
    require(result.returncode == 0, 'BOOTSTRAP_INVENTORY_UNAVAILABLE')
    return result.stdout


def address(text):
    require(isinstance(text, str), 'BOOTSTRAP_PRIVATE_ADDRESS_REQUIRED')
    try:
        ip = ipaddress.IPv4Address(text)
    except ValueError:
        raise Refusal('BOOTSTRAP_PRIVATE_ADDRESS_REQUIRED')
    networks = ['10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16']
    require(str(ip) == text and any(ip in ipaddress.IPv4Network(n) for n in networks),
            'BOOTSTRAP_PRIVATE_ADDRESS_REQUIRED')
    return ip


def validate(data):
    keys = {'id', 'role', 'sourceServerId', 'targetServerId', 'sourceAddress',
            'targetAddress', 'targetEndpoint', 'wireguardPort', 'sharedStateId',
            'controllerArtifactSha256', 'businessAllocationSlot'}
    require(isinstance(data, dict) and set(data) == keys, 'BOOTSTRAP_INVALID_INPUT')
    for key in ['id', 'sourceServerId', 'targetServerId', 'sharedStateId']:
        try:
            require(str(uuid.UUID(data[key])) == data[key] and uuid.UUID(data[key]).int != 0,
                    'BOOTSTRAP_INVALID_IDENTITY')
        except (ValueError, TypeError, AttributeError):
            raise Refusal('BOOTSTRAP_INVALID_IDENTITY')
    require(data['sourceServerId'] != data['targetServerId'], 'BOOTSTRAP_DISTINCT_HOSTS_REQUIRED')
    require(data['role'] in PACKAGES, 'BOOTSTRAP_INVALID_ROLE')
    require(address(data['sourceAddress']) != address(data['targetAddress']), 'BOOTSTRAP_DISTINCT_ADDRESSES_REQUIRED')
    host = data['targetEndpoint']
    require(isinstance(host, str) and len(host) <= 253 and '..' not in host
            and re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?', host),
            'BOOTSTRAP_INVALID_ENDPOINT')
    require(type(data['wireguardPort']) is int and 1024 <= data['wireguardPort'] <= 65535,
            'BOOTSTRAP_INVALID_WIREGUARD_PORT')
    require(type(data['businessAllocationSlot']) is int and 1 <= data['businessAllocationSlot'] <= 1023,
            'BOOTSTRAP_EXTERNAL_ALLOCATION_REQUIRED')
    require(isinstance(data['controllerArtifactSha256'], str)
            and re.fullmatch(r'[a-f0-9]{64}', data['controllerArtifactSha256']), 'BOOTSTRAP_INVALID_ARTIFACT')
    return data


def private(path, size=None):
    require(all(not parent.is_symlink() for parent in [path, *path.parents]), 'BOOTSTRAP_UNSAFE_PRIVATE_PATH')
    require(path.is_file(), 'BOOTSTRAP_PRIVATE_SOURCE_MISSING')
    info = path.stat()
    require(stat.S_IMODE(info.st_mode) == 0o600 and info.st_size <= 4 * 1024 * 1024,
            'BOOTSTRAP_UNSAFE_PRIVATE_PATH')
    value = path.read_bytes()
    require(size is None or len(value) == size, 'BOOTSTRAP_PRIVATE_SOURCE_CHANGED')
    return value


def packages(role):
    result = {}
    for package in PACKAGES[role]:
        value = command(['dpkg-query', '-W', '-f=${db:Status-Status} ${Version}', '--', package], allow_missing=True)
        if value is not None:
            status, _, version = value.decode().strip().partition(' ')
            result[package] = version if status == 'installed' else None
        else:
            result[package] = None
    return result


def operating_system():
    values = {}
    # /etc/os-release is a distribution-provided symlink on Debian/Ubuntu.
    for line in OS_RELEASE.read_text().splitlines():
        key, sep, value = line.partition('=')
        if sep:
            values[key] = value.strip('"')
    supported = {('debian', '13'), ('ubuntu', '24.04'), ('ubuntu', '26.04')}
    require((values.get('ID'), values.get('VERSION_ID')) in supported, 'BOOTSTRAP_UNSUPPORTED_DISTRIBUTION')
    require(platform.machine() in ['x86_64', 'aarch64'], 'BOOTSTRAP_UNSUPPORTED_ARCHITECTURE')
    require(SYSTEMD_RUNTIME.is_dir(), 'BOOTSTRAP_SYSTEMD_REQUIRED')
    return {'id': values['ID'], 'versionId': values['VERSION_ID'], 'architecture': platform.machine()}


def network(data):
    addresses = json.loads(command(['ip', '-j', '-4', 'address', 'show']))
    routes = json.loads(command(['ip', '-j', '-4', 'route', 'show', 'table', 'all']))
    role = data['role']
    own = data['sourceAddress'] if role == 'SOURCE' else data['targetAddress']
    peer = data['targetAddress'] if role == 'SOURCE' else data['sourceAddress']
    interfaces = [item for item in addresses if item['ifname'] == INTERFACE]
    if role == 'TARGET':
        require(not interfaces, 'BOOTSTRAP_EXISTING_TARGET_LINK')
    for interface in addresses:
        for value in interface.get('addr_info', []):
            if value.get('local') in [own, peer]:
                require(role == 'SOURCE' and interface['ifname'] == INTERFACE and value['local'] == own,
                        'BOOTSTRAP_ADDRESS_IN_USE')
    for route in routes:
        dest = route.get('dst', 'default')
        if dest == 'default':
            continue
        net = ipaddress.ip_network(dest, strict=False)
        for value in [own, peer]:
            if address(value) in net:
                require(role == 'SOURCE' and route.get('dev') == INTERFACE and value == own,
                        'BOOTSTRAP_ROUTE_CONFLICT')
    peers = None
    if interfaces:
        require(len(interfaces) == 1 and any(v.get('local') == own and v.get('prefixlen') == 32
                for v in interfaces[0].get('addr_info', [])), 'BOOTSTRAP_SOURCE_LINK_CONFLICT')
        peers = command(['wg', 'show', INTERFACE, 'allowed-ips']).decode()
        for line in peers.splitlines():
            columns = line.split()
            for item in columns[1:]:
                if item != '(none)':
                    for prefix in item.split(','):
                        require(address(peer) not in ipaddress.ip_network(prefix, strict=False),
                                'BOOTSTRAP_PEER_ALREADY_ALLOCATED')
        configuration_sha = digest(private(WIREGUARD / (INTERFACE + '.conf')))
        public_key = command(['wg', 'show', INTERFACE, 'public-key']).decode().strip()
    else:
        configuration_sha, public_key = None, None
    # Never collect `wg show dump`: its first row contains the private key.
    stable_addresses = [{'ifname': row['ifname'], 'flags': sorted(row.get('flags', [])),
                         'addresses': [{k: addr[k] for k in ['local', 'prefixlen', 'scope'] if k in addr}
                                       for addr in row.get('addr_info', [])]} for row in addresses]
    stable_routes = [{key: row[key] for key in ['dst', 'dev', 'gateway', 'prefsrc', 'table', 'type',
                                               'scope', 'protocol', 'metric'] if key in row} for row in routes]
    return {'addresses': stable_addresses, 'routes': stable_routes,
            'existingPeerMapSha256': digest(peers.encode()) if peers is not None else None,
            'existingConfigurationSha256': configuration_sha, 'existingPublicKey': public_key}


def source(data):
    require(CONTROL.is_dir() and not CONTROL.is_symlink(), 'BOOTSTRAP_SOURCE_CONTROL_MISSING')
    state = CONTROL / 'state'
    require(state.is_dir() and not state.is_symlink() and not os.path.ismount(state),
            'BOOTSTRAP_LOCAL_STATE_OWNER_REQUIRED')
    owner = state.stat()
    require(stat.S_IMODE(owner.st_mode) == 0o700 and pwd.getpwuid(owner.st_uid).pw_name == 'eatwhat-control',
            'BOOTSTRAP_UNSAFE_STATE_OWNER')
    require(private(state / 'controller-state-id', 36).decode() == data['sharedStateId'],
            'BOOTSTRAP_SHARED_STATE_ID_MISMATCH')
    shared_key_sha = digest(private(state / 'servers.key', 32))
    files = [CONTROL / 'config/application.properties', CONTROL / 'config/cluster-profiles.json',
             SYSTEM / 'eatwhat-control.service']
    proof = {}
    for path in files:
        require(not path.is_symlink(), 'BOOTSTRAP_UNSAFE_PRIVATE_PATH')
        content = path.read_bytes() if path.name.endswith('.service') else private(path)
        proof[str(path)] = digest(content)
    artifact = CONTROL / 'acceptance' / (data['controllerArtifactSha256'] + '.jar')
    # Large JARs are not handled by the small-private-file reader.
    require(artifact.is_file() and not artifact.is_symlink() and artifact.stat().st_mode & 0o777 == 0o600,
            'BOOTSTRAP_CONTROLLER_ARTIFACT_MISSING')
    with artifact.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    require(actual == data['controllerArtifactSha256'], 'BOOTSTRAP_CONTROLLER_ARTIFACT_CHANGED')
    proof[str(artifact)] = actual
    return {'stateOwnerUid': owner.st_uid, 'stateOwnerGid': owner.st_gid,
            '_sharedKeySha256': shared_key_sha, '_privateFiles': proof}


def target(data):
    # A retained or partially installed controller is never mistaken for an empty host.
    require(not CONTROL.exists() and not CONTROL.is_symlink(), 'BOOTSTRAP_TARGET_CONTROLLER_RETAINED')
    for path in [SYSTEM / 'eatwhat-control.service', SYSTEM / 'eatwhat-control.service.d',
                 WIREGUARD / (INTERFACE + '.conf'), WIREGUARD / (INTERFACE + '.key')]:
        require(not path.exists() and not path.is_symlink(), 'BOOTSTRAP_TARGET_CONFIGURATION_RETAINED')
    # Vendor units count too; checking /etc alone would overwrite an existing install.
    value = command(['systemctl', 'show', '--property=LoadState', '--value', 'eatwhat-control.service'])
    require(value.strip() == b'not-found', 'BOOTSTRAP_TARGET_CONTROLLER_RETAINED')
    for directory in [Path('/usr/lib/systemd/system'), Path('/lib/systemd/system')]:
        require(not (directory / 'eatwhat-control.service').exists(), 'BOOTSTRAP_TARGET_CONTROLLER_RETAINED')
    business = Path('/opt/eatwhat-sites') / data['targetServerId']
    require(not business.exists() and not business.is_symlink(), 'BOOTSTRAP_TARGET_BUSINESS_RETAINED')
    return {'controllerAbsent': True, 'businessAbsent': True}


def effects(data):
    # Public preview text describes the full intended composite operation. The
    # eventual executor must separately implement and prove every listed phase.
    return {
        'services': {'source': ['wireguard', 'nfs4-state-export', 'forwarding-only-ssh-account'],
                     'target': ['java21-controller', 'wireguard', 'nfs4-state-mount', 'dependency-ssh-tunnel',
                                'business-app', 'postgres', 'redis']},
        'packages': PACKAGES,
        'network': {'sourcePrivateAddress': data['sourceAddress'], 'targetPrivateAddress': data['targetAddress'],
                    'sourceAllowedIps': [data['targetAddress'] + '/32'],
                    'targetAllowedIps': [data['sourceAddress'] + '/32'],
                    'targetWireguardUdpPort': data['wireguardPort'], 'targetEndpoint': data['targetEndpoint'],
                    'defaultRouteChanged': False, 'dnsChanged': False, 'natEnabled': False,
                    'publicDatabaseEnabled': False, 'publicManagementEnabled': False},
        'sharedState': {'id': data['sharedStateId'], 'existingStateOnly': True,
                        'sourceOwnerMapping': 'ALL_SQUASH_TO_EXISTING_STATE_OWNER',
                        'mountRequiredBeforeControllerStarts': True},
        'controller': {'automaticTakeover': False, 'candidateInitially': False,
                       'administratorCopied': False},
        'business': {'mode': 'EXTERNAL', 'allocationSlot': data['businessAllocationSlot'],
                     'installAndJoinViaExistingProtocols': True},
        'approvalRequired': ['INSTALL_PACKAGES_AND_SYSTEM_SERVICES', 'ADD_EXACT_PRIVATE_PEER',
                             'SHARE_EXISTING_CONTROLLER_STATE_WITH_TARGET',
                             'CREATE_FORWARDING_ONLY_DEPENDENCY_CREDENTIAL',
                             'INSTALL_EXTERNAL_BUSINESS_AND_JOIN_CLUSTER'],
    }


def inspect(data):
    data = validate(data)
    require(os.geteuid() == 0 and sys.platform == 'linux', 'BOOTSTRAP_LINUX_ROOT_REQUIRED')
    os_info = operating_system()
    host = source(data) if data['role'] == 'SOURCE' else target(data)
    network_info = network(data)
    installed = packages(data['role'])
    memory = {}
    for line in (PROC / 'meminfo').read_text().splitlines():
        key, _, value = line.partition(':')
        if key in ['MemTotal', 'MemAvailable']:
            memory[key] = int(value.split()[0]) // 1024
    available_disk = shutil.disk_usage('/opt').free // 1048576
    require(memory.get('MemTotal', 0) >= 2048 and memory.get('MemAvailable', 0) >= 1024
            and available_disk >= 10240, 'BOOTSTRAP_INSUFFICIENT_RESOURCES')
    facts = {'os': os_info, 'host': host, 'network': network_info, 'packages': installed}
    # Volatile counters are not part of the frozen proof. A second resource
    # admission is still mandatory immediately before accepting side effects.
    proof = digest(canonical({'request': data, 'facts': facts}))
    return {'protocol': PROTOCOL, 'id': data['id'], 'role': data['role'], 'ready': True,
            'effectsStarted': False, 'proof': proof, 'facts': facts,
            'resources': {'totalMemoryMiB': memory['MemTotal'], 'availableMemoryMiB': memory['MemAvailable'],
                          'diskFreeMiB': available_disk, 'cpuCount': os.cpu_count()},
            'plannedEffects': effects(data), 'executorAvailable': False}


if __name__ == '__main__':
    try:
        require(len(sys.argv) == 2 and sys.argv[1] == 'inspect', 'BOOTSTRAP_INSPECT_ONLY')
        raw = sys.stdin.buffer.read(16385)
        require(len(raw) <= 16384, 'BOOTSTRAP_INPUT_TOO_LARGE')
        print(json.dumps(inspect(json.loads(raw))))
    except Refusal as failure:
        print(json.dumps({'protocol': PROTOCOL, 'state': 'REJECTED', 'code': failure.code, 'effectsStarted': False}))
        sys.exit(1)
    except Exception:
        print(json.dumps({'protocol': PROTOCOL, 'state': 'REJECTED', 'code': 'BOOTSTRAP_INVENTORY_UNAVAILABLE',
                          'effectsStarted': False}))
        sys.exit(1)
