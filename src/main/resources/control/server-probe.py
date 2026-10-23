import json, os, platform, shutil, subprocess, sys

def command(args):
    try:
        p = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=8, env={'PATH':'/usr/local/bin:/usr/bin:/bin'})
        return p.returncode == 0, p.stdout[:1024].decode('utf-8', 'replace').strip()
    except Exception:
        return False, ''

docker = shutil.which('docker')
docker_ok, docker_version = command([docker, '--version']) if docker else (False, '')
compose, version = command([docker, 'compose', 'version', '--short']) if docker else (False, '')
access, _ = command([docker, 'info', '--format', '{{.ServerVersion}}']) if docker else (False, '')
try:
    memory = int(next(line.split()[1] for line in open('/proc/meminfo') if line.startswith('MemTotal:'))) // 1024
except Exception:
    memory = 0
print(json.dumps({'protocol':'eatwhat-server-probe-v1', 'linux':platform.system()+' '+platform.release() if platform.system()=='Linux' else None, 'python':platform.python_version(), 'docker':docker_version if docker_ok else None, 'compose':version if compose else None, 'dockerAccess':access, 'root':os.geteuid()==0, 'memoryMiB':memory, 'diskFreeMiB':shutil.disk_usage('/').free//1048576}))
