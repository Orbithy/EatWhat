#!/usr/bin/env python3
"""Reproduce Linux filesystem checks using a cached, pinned official Python image."""
import subprocess,sys
from pathlib import Path

root=Path(__file__).resolve().parents[1]
image='python@sha256:70729b46c69b4f1e97c4822c1af3df53a1476cf5ddc6c087c0c10bc3a5678c2f'
if sys.argv[1:] not in [[],['--runner']]:raise SystemExit('usage: run-cluster-release-linux.py [--runner]')
args=['docker','run','--rm','--init','--pull','never','--network','none','--read-only','--memory','256m','--cpus','1','--user','0:0','--cap-drop','ALL','--cap-add','CHOWN','--cap-add','FOWNER','--cap-add','DAC_OVERRIDE','--security-opt','no-new-privileges','--tmpfs','/tmp:rw,mode=1777,size=16777216','--tmpfs','/opt/eatwhat-sites:rw,mode=700,size=2147483648']
for name in ['src/main/resources/control/cluster-release-core.py','src/main/resources/control/cluster-release.py','deploy/remote-release.py','scripts/test-cluster-release-core.py','scripts/verify-cluster-release-linux.py','scripts/verify-cluster-release-runner-linux.py']:
    args.extend(['--mount',f'type=bind,src={root/name},dst=/source/{name},readonly'])
entry='verify-cluster-release-runner-linux.py' if '--runner' in sys.argv else 'verify-cluster-release-linux.py'
args.extend([image,'python','-B','/source/scripts/'+entry])
subprocess.run(args,check=True)
