#!/usr/bin/env python3
"""Exercise the actual expanded agent via a Linux shell, with no installation.

Checks source embedding, command argument size and safe main-protocol errors.
No SSH, application, database, source mounts or host directories are accessed.
"""
import base64,json,subprocess,uuid,zlib
from pathlib import Path

root=Path(__file__).resolve().parents[1]
core=(root/'src/main/resources/control/cluster-release-core.py').read_text().replace('__EATWHAT_RELEASE_AGENT_BASE64__',base64.b64encode((root/'deploy/remote-release.py').read_bytes()).decode())
source=(root/'src/main/resources/control/cluster-release.py').read_text().replace('__EATWHAT_CLUSTER_RELEASE_CORE_BASE64__',base64.b64encode(core.encode()).decode())
encoded=base64.b64encode(zlib.compress(source.encode())).decode();command='python3 -c "exec(__import__(\'zlib\').decompress(__import__(\'base64\').b64decode(\''+encoded+'\')))" status'
header={'id':str(uuid.uuid4()),'serverId':str(uuid.uuid4()),'siteId':'synthetic','instanceId':str(uuid.uuid4()),'clusterId':str(uuid.uuid4()),'epoch':1,'databaseFingerprint':'a'*32,'artifactSha256':'b'*64,'fromSha256':'c'*64,'manifestHash':'d'*64,'restoreEvidenceId':'synthetic-no-installation'}
args=['docker','run','-i','--rm','--init','--pull','never','--network','none','--read-only','--memory','128m','--cpus','1','--cap-drop','ALL','--security-opt','no-new-privileges','--tmpfs','/opt/eatwhat-sites:rw,mode=700,size=1048576','python@sha256:70729b46c69b4f1e97c4822c1af3df53a1476cf5ddc6c087c0c10bc3a5678c2f','/bin/sh','-c',command]
result=subprocess.run(args,input=json.dumps(header).encode()+b'\n',stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
assert result.returncode==1,'Missing installation must reject without execution'
reply=json.loads(result.stdout);assert reply=={'protocol':'eatwhat-cluster-release-v1','code':'RELEASE_FILES_INCOMPLETE'},'Expanded agent did not reach the fixed protocol guard'
assert len(command.encode())<100000,'Bundled agent exceeds the reviewed SSH shell argument budget'
print(json.dumps({'status':'PASSED','scope':'ACTUAL_EXPANDED_AGENT_LINUX_SHELL_AND_SAFE_MAIN_ERROR','scriptBytes':len(source.encode()),'commandBytes':len(command.encode()),'errorCode':reply['code'],'ssh':False,'installationExists':False,'network':False}))
