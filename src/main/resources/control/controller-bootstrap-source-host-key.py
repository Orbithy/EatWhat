"""Read only: dedicated daemon identity, through the already pinned root SSH route.

The private host key stays on SOURCE. This does not trust a new network scan or
use an administrative login credential as the target's forwarding identity.
"""
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import uuid

PROTOCOL = 'eatwhat-controller-bootstrap-dependency-v1'


def require(value):
    if not value:
        raise ValueError('BOOTSTRAP_DEPENDENCY_SOURCE_HOST_KEY_UNAVAILABLE')


def main():
    data = json.loads(sys.stdin.buffer.read(1025))
    require(isinstance(data, dict) and set(data) == {'id'})
    identity = str(uuid.UUID(data['id']))
    require(identity == data['id'] and uuid.UUID(identity).int != 0 and os.geteuid() == 0)
    path = Path('/etc/ssh/ssh_host_ed25519_key')
    require(all(not parent.is_symlink() for parent in [path, *path.parents]))
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600 and info.st_size <= 16384)
    result = subprocess.run(['/usr/bin/ssh-keygen', '-y', '-f', str(path)], stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=8, check=True)
    # OpenSSH may append the stored comment, which is not part of the key.
    fields = result.stdout.decode('ascii').split()
    require(len(fields) >= 2)
    public = ' '.join(fields[:2])
    require(public.startswith('ssh-ed25519 ') and len(public) <= 256)
    print(json.dumps({'protocol': PROTOCOL, 'id': identity, 'effectsStarted': False, 'publicKey': public}))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print(json.dumps({'protocol': PROTOCOL, 'code': 'BOOTSTRAP_DEPENDENCY_SOURCE_HOST_KEY_UNAVAILABLE'}))
        sys.exit(1)
