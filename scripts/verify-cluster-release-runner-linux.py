#!/usr/bin/env python3
"""Real Linux fork/session/flock/fsync lifecycle with simulated services only.

Run via run-cluster-release-linux.py --runner. No SSH/Docker socket, real
database, runtime health or network is used. Fault injection affects only
processes spawned in this disposable container by this script.
"""
import base64, hashlib, importlib.util, io, json, os, select, shutil, signal, subprocess, sys, time
from pathlib import Path

REPO=Path(__file__).resolve().parents[1]
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
core=load('linux_release_core',REPO/'src/main/resources/control/cluster-release-core.py');core.RELEASE_SOURCE=base64.b64encode((REPO/'deploy/remote-release.py').read_bytes()).decode()
runner=load('linux_release_runner',REPO/'src/main/resources/control/cluster-release.py');runner.core=core
def require(value):
    if not value:raise AssertionError('Linux runner lifecycle acceptance failed')

def context(header):
    value=core.ReleaseContext(header);value.agent.base=[sys.executable,header['_fixtureCli'],header['_fixtureEvents'],header['_fixtureMode']]
    value.agent.runtime_sha=lambda:core.digest(value.agent.jar)
    value.agent.health=lambda *args:None
    value.agent.request=lambda *args:{key:header[key] for key in ['siteId','instanceId','clusterId','databaseFingerprint']}|{'protocolVersion':1,'dataPlaneReady':True,'mode':'CAMPUS','allocationSlot':0,'membership':{'epoch':header['epoch'],'state':'ACTIVE','pending_change':None}}
    value.agent.backup=lambda directory:runner.atomic(directory/'database.dump',b'simulated-database-backup')
    value.agent.wait_ready=lambda sha,*args:require(core.digest(value.agent.jar)==sha)
    return value

def protocol(header):
    value=runner.Runner(header,context);value.inspect_containers=lambda unused:None
    return value

if len(sys.argv)==3 and sys.argv[1]=='--caller':
    header=core.document(Path(sys.argv[2]));core.verify_authority=lambda unused:{'authorized':True}
    print(json.dumps(protocol(header).dispatch(io.BytesIO(b'approved-linux-fixture-candidate'))),flush=True)
    time.sleep(120) # Harness kills this caller session after the detach acknowledgement.
    sys.exit(0)

require(sys.platform=='linux' and os.geteuid()==0)
fixture=load('linux_runner_fixture',REPO/'scripts/test-cluster-release-core.py')
def prepare(mode):
    case=fixture.CoreChecks();case.setUp()
    for adaptation in reversed(case.patches):adaptation.stop()
    root=core.BASE/case.header['serverId'];shutil.copytree(case.root,root);old=str(case.root)
    for path in root.rglob('*'):
        if path.is_file():path.write_bytes(path.read_bytes().replace(old.encode(),str(root).encode()))
    compose=json.loads((root/'deploy/compose.json').read_text());compose['services']['app']['user']='10001:10001';(root/'deploy/compose.json').write_text(json.dumps(compose))
    config=json.loads((root/'deploy/release.json').read_text());config['command_timeout_seconds']=60;(root/'deploy/release.json').write_text(json.dumps(config))
    os.chown(root/'config',10001,10001);os.chown(root/'app-state',10001,10001)
    for name in ['application.properties','control.token']:os.chown(root/'config'/name,10001,10001)
    header=dict(case.header);candidate=b'approved-linux-fixture-candidate';header.update(artifactSha256=hashlib.sha256(candidate).hexdigest(),artifactBytes=len(candidate),timeoutSeconds=30)
    cli=case.base/'fixed-service-cli.py';events=case.base/'events.jsonl'
    cli.write_text('''import json,os,sys,time
from pathlib import Path
path=Path(sys.argv[1]);count=len(path.read_text().splitlines()) if path.exists() else 0
fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_APPEND,0o600)
with os.fdopen(fd,'w') as out:out.write(json.dumps({'worker':os.getppid(),'args':sys.argv[3:]})+'\\n');out.flush();os.fsync(out.fileno())
time.sleep(60 if sys.argv[2]=='watchdog' and count==0 else 2)
''')
    header.update(_fixtureCli=str(cli),_fixtureEvents=str(events),_fixtureMode=mode);header['configurationProof']=core.ReleaseContext(header).configuration_proof
    request=case.base/'request.json';runner.atomic(request,header)
    return case,root,header,request,events

def caller(request,case):
    errors=open(case.base/'caller-error.log','wb')
    process=subprocess.Popen([sys.executable,'-B',__file__,'--caller',str(request)],stdout=subprocess.PIPE,stderr=errors,start_new_session=True)
    try:
        require(bool(select.select([process.stdout],[],[],10)[0]));reply=json.loads(process.stdout.readline());require(reply['state']=='RUNNING');return process,errors
    except BaseException:
        if process.poll() is None:os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=3)
        errors.close();sys.stderr.write((case.base/'caller-error.log').read_text());raise

def wait_for(action,condition,seconds):
    deadline=time.monotonic()+seconds
    while True:
        value=action()
        if condition(value):return value
        require(time.monotonic()<deadline);time.sleep(0.1)

flows=[]
for mode in ['disconnect','worker-kill','watchdog']:
    case,root,header,request,events=prepare(mode);process=None;errors=None;began=time.monotonic()
    try:
        process,errors=caller(request,case);reader=protocol(header)
        # The child acknowledged setsid before the accepted reply. Killing the
        # entire original SSH-like caller session must leave its worker alive.
        os.killpg(process.pid,signal.SIGKILL);require(process.wait(timeout=3)==-signal.SIGKILL)
        require(reader.status()['state']=='RUNNING')
        first=wait_for(lambda:[json.loads(line) for line in events.read_text().splitlines()] if events.exists() else [],bool,5)[0]
        require(first['worker']!=process.pid)
        if mode=='worker-kill':
            os.kill(first['worker'],signal.SIGKILL)
            # The in-flight fixed CLI inherited the project flock. A dead
            # worker PID must not release it while its command is still live.
            require(reader.status()['state']=='RUNNING')
        result=wait_for(reader.status,lambda value:value['state']!='RUNNING',45)
        expected='ROLLED_BACK' if mode=='watchdog' else 'SUCCEEDED';require(result['state']==expected)
        data=(root/'releases/current.jar').read_bytes();require(data==(b'synthetic-running-artifact' if mode=='watchdog' else b'approved-linux-fixture-candidate'))
        calls=[json.loads(line)['args'] for line in events.read_text().splitlines()];require(calls==[['up','-d','--no-deps','--force-recreate','app']]*(2 if mode=='watchdog' else 1))
        require(core.digest(root/'releases/current.jar')==(header['fromSha256'] if mode=='watchdog' else header['artifactSha256']));require((root/'releases/current.jar').stat().st_uid==0) # Fixture original JAR is root-owned; ownership is preserved.
        if mode=='worker-kill':
            require(result.get('derived') is True);before=reader.receipt.read_bytes();require(core.document(reader.receipt)['state']=='RUNNING');require(before==reader.receipt.read_bytes())
            header['expectedRemoteState']='SUCCEEDED';core.read_authority=lambda unused:{'state':'SUCCEEDED','authorized':False};require(reader.seal()['sealedAfterAuthority'] is True)
        require(reader.dispatch(io.BytesIO(b'never-upload-or-reapply'))['state']==expected)
        require(calls==[json.loads(line)['args'] for line in events.read_text().splitlines()]);require('authorityToken' not in reader.journal())
        flows.append({'flow':mode,'state':expected,'seconds':round(time.monotonic()-began,2),'fixedAppRecreates':len(calls)})
    finally:
        if process and process.poll() is None:os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=3)
        if errors:errors.close()
        case.tearDown()
print(json.dumps({'status':'PASSED','scope':'REAL_LINUX_PROCESS_AND_JOURNAL_LIFECYCLE_WITH_SIMULATED_SERVICES','flows':flows,'network':False,'ssh':False,'realDatabaseOrApplication':False}))
