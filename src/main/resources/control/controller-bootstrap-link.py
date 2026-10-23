"""Durable exact-peer phase; no HTTP entry point or full installer yet.

Uses the completed, approval-bound local runtime and its key. Never replaces a
source interface or other peers, adjusts default routes/DNS/NAT or prints keys.
CONFIGURED is a local configuration proof, not an end-to-end health claim.
"""
import base64
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
import time
import types
import uuid

RUNTIME_SOURCE = '__EATWHAT_CONTROLLER_RUNTIME_BASE64__'
_RUNTIME = None
PROTOCOL = 'eatwhat-controller-bootstrap-link-v1'
BASE = Path('/var/lib/eatwhat-controller-bootstrap-link')
WIREGUARD = Path('/etc/wireguard')
INTERFACE = 'ewctrl0'
UNIT = 'wg-quick@ewctrl0.service'
MAX_INPUT = 524288


class Refusal(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def require(value, code='BOOTSTRAP_LINK_PRECONDITION_FAILED'):
    if not value:
        raise Refusal(code)


def runtime():
    global _RUNTIME
    if _RUNTIME is None:
        value = types.ModuleType('reviewed_bootstrap_runtime')
        exec(compile(base64.b64decode(RUNTIME_SOURCE), '<reviewed-runtime>', 'exec'), value.__dict__)
        _RUNTIME = value
    return _RUNTIME


def public(value):
    require(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9+/]{43}=', value), 'BOOTSTRAP_LINK_INVALID_PUBLIC_KEY')
    raw = base64.b64decode(value, validate=True)
    require(len(raw) == 32 and raw != b'\0' * 32 and base64.b64encode(raw).decode() == value,
            'BOOTSTRAP_LINK_INVALID_PUBLIC_KEY')
    return value


def validate(data, agent=False):
    fields = {'id', 'runtimeId', 'runtimeBinding', 'approvalDigest', 'peerPublicKey', 'agentSha256'}
    require(isinstance(data, dict) and set(data) == fields | ({'agent'} if agent else set()), 'BOOTSTRAP_LINK_INVALID_INPUT')
    for key in ['id', 'runtimeId']:
        try:
            require(str(uuid.UUID(data[key])) == data[key] and uuid.UUID(data[key]).int != 0, 'BOOTSTRAP_LINK_INVALID_IDENTITY')
        except (TypeError, ValueError, AttributeError):
            raise Refusal('BOOTSTRAP_LINK_INVALID_IDENTITY')
    require(data['id'] != data['runtimeId'], 'BOOTSTRAP_LINK_INVALID_IDENTITY')
    for key in ['runtimeBinding', 'approvalDigest', 'agentSha256']:
        require(isinstance(data[key], str) and re.fullmatch(r'[a-f0-9]{64}', data[key]), 'BOOTSTRAP_LINK_INVALID_BINDING')
    public(data['peerPublicKey'])
    if agent:
        require(isinstance(data['agent'], str) and len(data['agent'].encode()) <= MAX_INPUT
                and runtime().digest(data['agent'].encode()) == data['agentSha256'], 'BOOTSTRAP_LINK_AGENT_CHANGED')
    return data


def binding(data):
    return runtime().digest(runtime().canonical({k: v for k, v in data.items() if k != 'agent'}))


def fields(data):
    return {'protocol': PROTOCOL, 'id': data['id'], 'runtimeId': data['runtimeId'],
            'binding': binding(data), 'approvalDigest': data['approvalDigest']}


def task(data):
    return BASE / data['id']


def configuration():
    return WIREGUARD / (INTERFACE + '.conf')


def run(args, stdin=None, timeout=10):
    return runtime().run(args, stdin=stdin, timeout=timeout)


def completed_runtime(data):
    r = runtime()
    parent = r.BASE / data['runtimeId']
    header = r.validate(json.loads(r.private(parent / 'runtime-input.json')))
    require(header['request']['id'] == data['runtimeId'] and r.binding(header) == data['runtimeBinding']
            and header['approvalDigest'] == data['approvalDigest'], 'BOOTSTRAP_LINK_RUNTIME_BINDING_CHANGED')
    require(r.digest(r.private(parent / 'runtime-agent.py')) == header['agentSha256'], 'BOOTSTRAP_LINK_RUNTIME_AGENT_CHANGED')
    ready = r.status(header)
    require(ready['state'] == 'RUNTIME_READY' and public(ready['publicKey']) != data['peerPublicKey'],
            'BOOTSTRAP_LINK_RUNTIME_NOT_READY')
    original = json.loads(r.private(parent / 'preflight.json'))
    require(r.digest(r.canonical({'request': header['request'], 'facts': original['facts']})) == header['expectedProof'],
            'BOOTSTRAP_LINK_RUNTIME_PROOF_CHANGED')
    return header, ready, original


def key(data, header, ready):
    r = runtime()
    proof = json.loads(r.private(r.BASE / data['runtimeId'] / 'runtime-key-proof.json'))
    require(proof['publicKey'] == ready['publicKey'] and proof['keySource'] == ready['keySource'],
            'BOOTSTRAP_LINK_LOCAL_KEY_CHANGED')
    if ready['keySource'] == 'EXISTING_SOURCE_INTERFACE':
        require(header['request']['role'] == 'SOURCE', 'BOOTSTRAP_LINK_LOCAL_KEY_CHANGED')
        require(run(['wg', 'show', INTERFACE, 'public-key']).decode().strip() == ready['publicKey'],
                'BOOTSTRAP_LINK_LOCAL_KEY_CHANGED')
        return None
    require(ready['keySource'] == 'LOCAL_JOB_KEY', 'BOOTSTRAP_LINK_LOCAL_KEY_CHANGED')
    secret = r.private(r.BASE / data['runtimeId'] / 'wireguard.key', 64)
    require(r.digest(secret) == proof['privateKeySha256']
            and run(['wg', 'pubkey'], stdin=secret).decode().strip() == ready['publicKey'],
            'BOOTSTRAP_LINK_LOCAL_KEY_CHANGED')
    return secret


def peer_map():
    text = run(['wg', 'show', INTERFACE, 'allowed-ips']).decode()
    peers = {}
    for line in text.splitlines():
        cols = line.split()
        require(len(cols) >= 2 and public(cols[0]) not in peers, 'BOOTSTRAP_LINK_INVALID_PEER_MAP')
        values = []
        for word in cols[1:]:
            for prefix in word.split(','):
                if prefix != '(none)':
                    values.append(str(ipaddress.ip_network(prefix, strict=False)))
        peers[cols[0]] = sorted(values)
    return peers


def service(field):
    return run(['systemctl', 'show', '--property=' + field, '--value', UNIT]).decode().strip()


def external_network(network):
    return {'addresses': [x for x in network['addresses'] if x['ifname'] != INTERFACE],
            'routes': [x for x in network['routes'] if x.get('dev') != INTERFACE]}


def current_network():
    # Same stable fields used by preflight, without querying private WG values.
    addresses = json.loads(run(['ip', '-j', '-4', 'address', 'show']))
    routes = json.loads(run(['ip', '-j', '-4', 'route', 'show', 'table', 'all']))
    return {'addresses': [{'ifname': row['ifname'], 'flags': sorted(row.get('flags', [])),
                          'addresses': [{k: addr[k] for k in ['local', 'prefixlen', 'scope'] if k in addr}
                                        for addr in row.get('addr_info', [])]} for row in addresses],
            'routes': [{k: row[k] for k in ['dst', 'dev', 'gateway', 'prefsrc', 'table', 'type', 'scope', 'protocol', 'metric'] if k in row}
                       for row in routes]}


def snapshot(data):
    r = runtime()
    header, ready, original = completed_runtime(data)
    require(not r.POLICY.exists() and not r.POLICY.is_symlink(), 'BOOTSTRAP_LINK_PACKAGE_PHASE_RETAINED')
    r.verify_service_guards(header)
    current = r.preflight().inspect(header['request'])
    require({k: v for k, v in current['facts'].items() if k != 'packages'} ==
            {k: v for k, v in original['facts'].items() if k != 'packages'}, 'BOOTSTRAP_LINK_PLAN_CHANGED')
    require(all(current['facts']['packages'].values()), 'BOOTSTRAP_LINK_RUNTIME_NOT_READY')
    secret = key(data, header, ready)
    existing = ready['keySource'] == 'EXISTING_SOURCE_INTERFACE'
    request = header['request']
    own = request['sourceAddress'] if request['role'] == 'SOURCE' else request['targetAddress']
    other = request['targetAddress'] if request['role'] == 'SOURCE' else request['sourceAddress']
    if existing:
        require(service('ActiveState') == 'active' and service('UnitFileState') == 'enabled',
                'BOOTSTRAP_LINK_SOURCE_SERVICE_UNMANAGED')
        before = r.private(configuration())
        # Keep arbitrary existing peer sections byte-for-byte. These two flags
        # would defeat our persisted exact-route and immutable-config contract.
        section = ''
        for line in before.decode().splitlines():
            item = line.split('#', 1)[0].strip()
            if item.startswith('['):
                section = item
            elif section == '[Interface]' and '=' in item:
                name, value = [x.strip() for x in item.split('=', 1)]
                require(name != 'SaveConfig' or value.lower() == 'false', 'BOOTSTRAP_LINK_SOURCE_CONFIG_UNSUPPORTED')
                require(name != 'Table' or value == 'auto', 'BOOTSTRAP_LINK_SOURCE_CONFIG_UNSUPPORTED')
        peers = peer_map()
        require(data['peerPublicKey'] not in peers, 'BOOTSTRAP_LINK_PEER_ALREADY_PRESENT')
        listen = run(['wg', 'show', INTERFACE, 'listen-port']).decode().strip()
    else:
        require(not configuration().exists() and not configuration().is_symlink()
                and not (WIREGUARD / (INTERFACE + '.key')).exists()
                and not (WIREGUARD / (INTERFACE + '.key')).is_symlink(), 'BOOTSTRAP_LINK_CONFIGURATION_RETAINED')
        require(service('ActiveState') == 'inactive' and service('UnitFileState') in ['disabled', 'indirect'],
                'BOOTSTRAP_LINK_SERVICE_RETAINED')
        for directory in [r.SYSTEM, Path('/run/systemd/system'), Path('/run/systemd/system.control'), Path('/etc/systemd/system.control')]:
            for name in [UNIT, UNIT + '.d', 'wg-quick@.service', 'wg-quick@.service.d']:
                candidate = directory / name
                require(not candidate.exists() and not candidate.is_symlink(), 'BOOTSTRAP_LINK_SERVICE_OVERRIDE_RETAINED')
        if request['role'] == 'TARGET':
            require(not run(['ss', '-H', '-l', '-u', '-n', 'sport = :' + str(request['wireguardPort'])]).strip(),
                    'BOOTSTRAP_LINK_UDP_PORT_IN_USE')
        before, peers, listen = None, {}, None
    # Snapshot only contains public key maps, stable network and file digests.
    result = {'role': request['role'], 'ownAddress': own, 'peerAddress': other,
              'ownPublicKey': ready['publicKey'], 'existingInterface': existing,
              'previousPeers': peers, 'previousListenPort': listen,
              'externalNetwork': external_network(current['facts']['network']),
              'previousWgAddresses': [x for x in current['facts']['network']['addresses'] if x['ifname'] == INTERFACE],
              'previousWgRoutes': [x for x in current['facts']['network']['routes'] if x.get('dev') == INTERFACE],
              'beforeConfigurationSha256': r.digest(before) if before is not None else None}
    return result, header, secret, before


def desired(data, header, secret, before):
    request = header['request']
    source = request['role'] == 'SOURCE'
    own = request['sourceAddress'] if source else request['targetAddress']
    other = request['targetAddress'] if source else request['sourceAddress']
    if before is None:
        require(secret is not None, 'BOOTSTRAP_LINK_LOCAL_KEY_CHANGED')
        text = '[Interface]\nAddress = ' + own + '/32\nPrivateKey = ' + secret.decode().strip() + '\n'
        if not source:
            text += 'ListenPort = ' + str(request['wireguardPort']) + '\n'
    else:
        text = before.decode()
    text += '\n[Peer]\nPublicKey = ' + data['peerPublicKey'] + '\nAllowedIPs = ' + other + '/32\n'
    if source:
        text += 'Endpoint = ' + request['targetEndpoint'] + ':' + str(request['wireguardPort']) + '\nPersistentKeepalive = 25\n'
    return text.encode()


def verify(data, intent):
    r = runtime()
    header, ready, original = completed_runtime(data)
    key(data, header, ready)
    require(r.digest(r.private(configuration())) == intent['configurationSha256'], 'BOOTSTRAP_LINK_CONFIGURATION_CHANGED')
    require(run(['wg', 'show', INTERFACE, 'public-key']).decode().strip() == intent['ownPublicKey'],
            'BOOTSTRAP_LINK_LOCAL_KEY_CHANGED')
    expected = dict(intent['previousPeers'], **{data['peerPublicKey']: [intent['peerAddress'] + '/32']})
    require(peer_map() == expected, 'BOOTSTRAP_LINK_PEERS_CHANGED')
    network = current_network()
    require(external_network(network) == intent['externalNetwork'], 'BOOTSTRAP_LINK_EXTERNAL_NETWORK_CHANGED')
    require(all(route in network['routes'] for route in intent['previousWgRoutes']), 'BOOTSTRAP_LINK_PREVIOUS_ROUTE_MISSING')
    interfaces = [x for x in network['addresses'] if x['ifname'] == INTERFACE]
    require(len(interfaces) == 1 and 'UP' in interfaces[0]['flags'], 'BOOTSTRAP_LINK_INTERFACE_NOT_UP')
    values = interfaces[0]['addresses']
    expected_addresses = intent['previousWgAddresses'][0]['addresses'] if intent['existingInterface'] else [
        {'local': intent['ownAddress'], 'prefixlen': 32, 'scope': 'global'}]
    require(values == expected_addresses, 'BOOTSTRAP_LINK_ADDRESSES_CHANGED')
    peer_route = False
    for route in network['routes']:
        if route.get('dev') != INTERFACE:
            continue
        if route in intent['previousWgRoutes']:
            continue
        prefix = str(ipaddress.ip_network(route.get('dst', '0.0.0.0/0'), strict=False))
        is_peer = prefix == intent['peerAddress'] + '/32' and route.get('table', 'main') == 'main' and 'gateway' not in route
        is_own = (not intent['existingInterface'] and prefix == intent['ownAddress'] + '/32'
                  and route.get('type') == 'local' and route.get('table') == 'local')
        require(is_peer or is_own, 'BOOTSTRAP_LINK_UNEXPECTED_ROUTE')
        peer_route |= is_peer
    require(peer_route, 'BOOTSTRAP_LINK_PEER_ROUTE_MISSING')
    require(service('ActiveState') == 'active' and service('UnitFileState') == 'enabled', 'BOOTSTRAP_LINK_SERVICE_NOT_PERSISTENT')
    if intent['role'] == 'TARGET':
        require(run(['wg', 'show', INTERFACE, 'listen-port']).decode().strip() == str(header['request']['wireguardPort']),
                'BOOTSTRAP_LINK_LISTEN_PORT_CHANGED')
    handshake = False
    for line in run(['wg', 'show', INTERFACE, 'latest-handshakes']).decode().splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0] == data['peerPublicKey']:
            handshake = int(parts[1]) > 0
    return {'role': intent['role'], 'interface': INTERFACE, 'ownPublicKey': intent['ownPublicKey'],
            'peerPublicKey': data['peerPublicKey'], 'address': intent['ownAddress'] + '/32',
            'allowedIps': [intent['peerAddress'] + '/32'], 'existingPeersPreserved': True,
            'defaultRouteChanged': False, 'dnsChanged': False, 'natEnabled': False,
            'handshakeSeen': handshake, 'endToEndHealthProven': False}


def lock(create=False):
    r = runtime()
    if create and not BASE.exists():
        BASE.mkdir(mode=0o700)
    r.directory(BASE)
    path = BASE / 'host.lock'
    if create and not path.exists():
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.close(fd)
    r.private(path)
    stream = path.open('r+')
    try:
        fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except Exception:
        stream.close()
        raise Refusal('BOOTSTRAP_LINK_HOST_BUSY')
    return stream


def receipt(data):
    path = task(data) / 'link-receipt.json'
    if not path.exists() and not path.is_symlink():
        return None
    value = json.loads(runtime().private(path))
    require(all(value.get(k) == v for k, v in fields(data).items()), 'BOOTSTRAP_LINK_BINDING_CHANGED')
    return value


def unit(data):
    return 'eatwhat-controller-link-' + data['id']


def status(data):
    validate(data)
    r = runtime()
    if not BASE.exists() and not BASE.is_symlink():
        return {**fields(data), 'state': 'NOT_STARTED', 'effectsStarted': False}
    r.directory(BASE)
    reserve = BASE / 'reservation.json'
    if reserve.exists() or reserve.is_symlink():
        require(json.loads(r.private(reserve)) == {'id': data['id'], 'binding': binding(data)}, 'BOOTSTRAP_LINK_HOST_RESERVED')
    else:
        require(all(p.name == 'host.lock' for p in BASE.iterdir()), 'BOOTSTRAP_LINK_RESERVATION_MISSING')
    saved = receipt(data)
    if saved is None:
        return {**fields(data), 'state': 'OUTCOME_UNKNOWN' if reserve.exists() else 'NOT_STARTED', 'effectsStarted': False}
    if saved['state'] == 'CONFIGURED':
        try:
            proof = verify(data, json.loads(r.private(task(data) / 'link-intent.json')))
            return {**saved, **proof}
        except Exception:
            return {**saved, 'state': 'OUTCOME_UNKNOWN', 'code': 'BOOTSTRAP_LINK_CONFIGURATION_UNVERIFIED'}
    if saved['state'] in ['ACCEPTED', 'RUNNING'] and time.time() - saved['acceptedAt'] >= 15:
        try:
            active = run(['systemctl', 'show', '--property=ActiveState', '--value', unit(data)]).strip()
            if active in [b'inactive', b'failed']:
                with lock():
                    require(saved.get('effectsStarted') is True, 'BOOTSTRAP_LINK_ACCEPTANCE_UNCONFIRMED')
                    intent = json.loads(r.private(task(data) / 'link-intent.json'))
                    proof = verify(data, intent)
                    return {**saved, **proof, 'state': 'CONFIGURED', 'derivedFromConfiguration': True}
            require(active in [b'active', b'activating', b'deactivating', b'reloading'])
        except Exception:
            return {**saved, 'state': 'OUTCOME_UNKNOWN', 'code': 'BOOTSTRAP_LINK_RECONCILIATION_REQUIRED'}
    return saved


def dispatch(data):
    validate(data, True)
    header = {k: v for k, v in data.items() if k != 'agent'}
    previous = status(header)
    if previous['state'] != 'NOT_STARTED':
        return previous
    snapshot(header)
    r = runtime()
    with lock(True):
        previous = status(header)
        if previous['state'] != 'NOT_STARTED':
            return previous
        r.atomic(BASE / 'reservation.json', {'id': data['id'], 'binding': binding(data)})
        path = task(data)
        require(not path.exists() and not path.is_symlink(), 'BOOTSTRAP_LINK_TASK_RETAINED')
        path.mkdir(mode=0o700)
        r.atomic(path / 'link-input.json', header)
        r.atomic(path / 'link-agent.py', data['agent'].encode())
        saved = {**fields(data), 'state': 'ACCEPTED', 'acceptedAt': time.time(), 'effectsStarted': False}
        r.atomic(path / 'link-receipt.json', saved)
    try:
        run(['systemd-run', '--quiet', '--no-block', '--unit=' + unit(data), '--property=Type=exec', '--property=UMask=0077',
             '--property=RuntimeMaxSec=120', '--property=StandardOutput=null', '--property=StandardError=null',
             '/usr/bin/python3', str(path / 'link-agent.py'), 'run', data['id']], timeout=15)
    except Exception:
        return {**saved, 'state': 'OUTCOME_UNKNOWN', 'code': 'BOOTSTRAP_LINK_DISPATCH_UNCONFIRMED'}
    return saved


def perform(id):
    require(str(uuid.UUID(id)) == id and uuid.UUID(id).int != 0, 'BOOTSTRAP_LINK_INVALID_IDENTITY')
    r = runtime()
    path = BASE / id
    data = validate(json.loads(r.private(path / 'link-input.json')))
    require(data['id'] == id and r.digest(r.private(path / 'link-agent.py')) == data['agentSha256'], 'BOOTSTRAP_LINK_AGENT_CHANGED')
    with lock():
        require(json.loads(r.private(BASE / 'reservation.json')) == {'id': id, 'binding': binding(data)}, 'BOOTSTRAP_LINK_HOST_RESERVED')
        saved = receipt(data)
        require(saved is not None and saved['state'] == 'ACCEPTED', 'BOOTSTRAP_LINK_ALREADY_DISPATCHED')
        try:
            intent, header, secret, before = snapshot(data)
            config = desired(data, header, secret, before)
            intent['configurationSha256'] = r.digest(config)
            r.atomic(path / 'link-intent.json', intent)
            if before is not None:
                r.atomic(path / 'before.conf', before)
            r.atomic(path / 'desired.conf', config)
            saved.update(state='RUNNING', effectsStarted=True)
            r.atomic(path / 'link-receipt.json', saved)
            if not WIREGUARD.exists():
                WIREGUARD.mkdir(mode=0o700)
            r.directory(WIREGUARD)
            # Recheck immediately before replacing only this config; no command
            # rebuilds an existing interface or replaces all of its live peers.
            if before is not None:
                require(r.private(configuration()) == before, 'BOOTSTRAP_LINK_CONFIGURATION_CHANGED')
            else:
                require(not configuration().exists() and not configuration().is_symlink(), 'BOOTSTRAP_LINK_CONFIGURATION_RETAINED')
            r.atomic(configuration(), config)
            if intent['existingInterface']:
                run(['wg', 'set', INTERFACE, 'peer', data['peerPublicKey'], 'allowed-ips', intent['peerAddress'] + '/32',
                     'endpoint', header['request']['targetEndpoint'] + ':' + str(header['request']['wireguardPort']),
                     'persistent-keepalive', '25'])
                require(run(['wg', 'show', INTERFACE, 'listen-port']).decode().strip() == intent['previousListenPort'],
                        'BOOTSTRAP_LINK_LISTEN_PORT_CHANGED')
                run(['ip', '-4', 'route', 'add', intent['peerAddress'] + '/32', 'dev', INTERFACE])
            else:
                run(['systemctl', 'enable', '--now', UNIT], timeout=45)
            proof = verify(data, intent)
            saved.update(proof, state='CONFIGURED', finishedAt=time.time())
        except Exception as error:
            saved.update(state='FAILED', code=error.code if isinstance(error, Refusal) else 'BOOTSTRAP_LINK_FAILED_PRESERVED',
                         finishedAt=time.time())
        r.atomic(path / 'link-receipt.json', saved)
        return saved


if __name__ == '__main__':
    try:
        require(os.geteuid() == 0 and sys.platform == 'linux', 'BOOTSTRAP_LINK_LINUX_ROOT_REQUIRED')
        if len(sys.argv) == 3 and sys.argv[1] == 'run':
            result = perform(sys.argv[2])
        else:
            require(len(sys.argv) == 2 and sys.argv[1] in ['dispatch', 'status'], 'BOOTSTRAP_LINK_INVALID_COMMAND')
            raw = sys.stdin.buffer.read(MAX_INPUT + 1)
            require(len(raw) <= MAX_INPUT, 'BOOTSTRAP_LINK_INPUT_TOO_LARGE')
            data = json.loads(raw)
            result = dispatch(data) if sys.argv[1] == 'dispatch' else status(data)
        print(json.dumps(result))
    except Exception as error:
        print(json.dumps({'protocol': PROTOCOL, 'code': error.code if isinstance(error, Refusal) else 'BOOTSTRAP_LINK_REFUSED'}))
        sys.exit(1)
