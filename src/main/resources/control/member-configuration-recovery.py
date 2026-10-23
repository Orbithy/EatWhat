"""Explicit restoration of a terminal failed, never joined member configuration.
New UUID and receipt; the original task remains immutable. No SQL writes or volume changes.
"""
import base64, fcntl, json, os, sys, time, types, urllib.request, uuid
from pathlib import Path

MEMBER_SOURCE='__EATWHAT_MEMBER_BASE64__'
_MEMBER=None
PROTOCOL='eatwhat-member-configuration-recovery-v1'
def member():
    global _MEMBER
    if _MEMBER is None:
        m=types.ModuleType('reviewed_member_agent');exec(compile(base64.b64decode(MEMBER_SOURCE),'<reviewed-member>','exec'),m.__dict__);_MEMBER=m
    return _MEMBER
def require(value):member().require(value)
def read(path,limit=262144):return member().read(path,limit)
def validate(h):
    require(str(uuid.UUID(h['failedId']))==h['failedId'] and h['failedId']!=h['id'])
    return member().validated(h)
def directory(h,create=False):
    root=validate(h);m=member();p=root/'private/member-configuration-recoveries'
    if create:p.mkdir(mode=0o700,exist_ok=True)
    if p.exists() or p.is_symlink():m.directory_check(p,{m.recovery().ROOT_UID})
    p=p/h['id']
    if create:p.mkdir(mode=0o700)
    if p.exists() or p.is_symlink():m.directory_check(p,{m.recovery().ROOT_UID})
    return p
def fields(h):return {k:h[k] for k in ['id','failedId','serverId','siteId','instanceId','clusterId','allocationSlot','artifactSha256']}
def stopped(unit):
    state=member().recovery().bootstrap().run(['systemctl','show','--property=ActiveState','--value',unit],timeout=5).decode().strip()
    require(state in ['inactive','failed'])
def status(h,observe_worker=True):
    p=directory(h)/'receipt.json'
    if not p.exists():return {'protocol':PROTOCOL,**fields(h),'state':'NOT_STARTED'}
    result=json.loads(read(p));require(all(result.get(k)==v for k,v in fields(h).items()))
    if observe_worker and result.get('state')=='ACCEPTED' and time.time()-result.get('acceptedAt',0)>15:
        try:
            stopped('eatwhat-member-recovery-'+h['id']);result.update(state='OUTCOME_UNKNOWN',code='MEMBER_RECOVERY_RECONCILIATION_REQUIRED')
            if result.get('effectsStarted') is True:
                # A stopped worker may have restored and verified everything but
                # died before sealing its receipt. Derive completion read-only;
                # never rewrite the original receipt or repeat any Docker command.
                loaded=load(h);require(loaded['currentProperties']==loaded['properties'] and all(value is None for value in loaded['authFiles'].values()));probe(h,loaded)
                result.update(state='RESTORED',stage='VERIFIED',databaseFingerprint=loaded['before']['fingerprint'],originalDatabasePreserved=True,clusterMembership='NOT_JOINED',newEnrollmentPlanRequired=True,derivedFromRuntime=True);result.pop('code',None)
        except Exception:pass
    return result

def load(h,locks=True):
    root=validate(h);m=member();r=m.recovery();b=r.bootstrap()
    if locks:
        r.assert_idle(root/'installation.lock');r.assert_idle(b.BASE/'.project-locks'/('eatwhat-site-'+h['serverId'][:8]+'.lock'))
    original=dict(h,id=h['failedId']);source=m.directory(original)
    old=json.loads(read(source/'input.json'));require(old['id']==h['failedId'])
    for key in ['serverId','siteId','instanceId','clusterId','allocationSlot','artifactSha256']:require(old.get(key)==h[key])
    receipt=m.status(old,False);require(receipt.get('state')=='FAILED' and receipt.get('code')=='MEMBER_CONFIGURATION_FAILED_PRESERVED')
    stopped('eatwhat-member-'+h['failedId'])
    # Any uncertain recovery remains fenced. A new UUID never repeats an ambiguous command.
    parent=directory(h).parent
    if parent.exists():
        for d in parent.iterdir():
            if d.name==h['id']:continue
            m.directory_check(d,{r.ROOT_UID});prior=json.loads(read(d/'receipt.json'))
            require(prior.get('failedId')!=h['failedId'] or prior.get('state') in ['RESTORED','FAILED','REJECTED'])
            if prior.get('failedId')==h['failedId']:stopped('eatwhat-member-recovery-'+d.name)
    raw=read(source/'original.properties');before=json.loads(read(source/'database-identity.json'));m.runtime(old)
    require(before['node']['site_id']==h['siteId'] and before['node']['instance_id']==h['instanceId'] and before['node']['mode']=='EXTERNAL')
    require(all(before['allocation'].get(k)==v for k,v in {'cluster_id':h['clusterId'],'site_id':h['siteId'],'instance_id':h['instanceId'],'slot':h['allocationSlot']}.items()))
    require(all(before[k]==0 for k in ['members','changes','localStates','users','outbox','inbox']))
    installed=read(root/'installation.json');installation=json.loads(installed)
    require(installation.get('state')=='SUCCEEDED' and installation.get('clusterMembership')=='NOT_JOINED' and installation.get('mode')=='EXTERNAL')
    for key in ['serverId','siteId','instanceId','clusterId','allocationSlot','artifactSha256']:require(installation.get(key)==h[key])
    require(r.digest(root/'releases/current.jar')==h['artifactSha256']);m.runtime_capable(root/'releases/current.jar',bool(old['runtime']['authCaPem']))
    require(read(root/'config/control.token',256).decode().strip()==old['runtime']['nodeToken'])
    original_properties=r.properties(raw);require(original_properties.get('app.cluster.enabled')=='false' and original_properties.get('app.control.enrollment-required')=='true')
    document_raw=read(root/'deploy/compose.json');document=json.loads(document_raw);port,images,env=r.validate_compose(root,document);r.preflight({'root':root,'compose':document,'images':images},h)
    # Prove the backup is the exact original frozen configuration, not an arbitrary replacement.
    original_proof={'properties':m.digest(raw),'compose':m.digest(document_raw),'installation':m.digest(installed),'jar':h['artifactSha256'],'database':before,'runtime':old['runtime']}
    if 'bootstrapContext' in old:
        originals={}
        for name,saved in [('deploy/compose.json','original-compose.json'),('deploy/.env','original.env'),('installation.json','original-installation.json')]:
            value=read(source/saved);require(read(root/name)==value);originals[name]=m.digest(value)
        original_proof['originalFiles']=originals
    require(m.digest(m.canonical(original_proof))==old['freezeHash'])
    current=read(root/'config/application.properties');require(current in [raw,m.edited(raw,m.runtime(old))])
    files={}
    for name,key in [('authority-auth.password','authPassword'),('authority-ca.pem','authCaPem')]:
        path=root/'config'/name
        if path.exists() or path.is_symlink():
            data=read(path,65536);require(bool(old['runtime'][key]) and data==old['runtime'][key].encode());files[name]=m.digest(data)
        else:files[name]=None
    actual=m.identity(root,env);require(actual==before)
    proof={'originalInput':m.digest(read(source/'input.json')),'originalReceipt':m.digest(read(source/'receipt.json')),'originalFreeze':old['freezeHash'],'currentProperties':m.digest(current),'authFiles':files,'database':actual}
    return {'root':root,'properties':raw,'currentProperties':current,'authFiles':files,'before':before,'environment':env,'httpPort':port,'nodeToken':old['runtime']['nodeToken'],'freezeHash':m.digest(m.canonical(proof))}
def inspect(h):
    loaded=load(h);return {'protocol':PROTOCOL,**fields(h),'ready':True,'freezeHash':loaded['freezeHash'],'databaseFingerprint':loaded['before']['fingerprint'],'clusterMembership':'NOT_JOINED','originalDatabasePreserved':True}
def dispatch(h):
    prior=status(h)
    if prior['state']!='NOT_STARTED':return prior
    try:require(inspect(h)['freezeHash']==h['freezeHash'])
    except Exception:return {'protocol':PROTOCOL,**fields(h),'state':'REJECTED','effectsStarted':False}
    agent=h.pop('agent');require(isinstance(agent,str) and len(agent)<=524288)
    d=directory(h,True);b=member().recovery().bootstrap();b.atomic(d/'input.json',h);b.atomic(d/'agent.py',agent.encode())
    receipt={'protocol':PROTOCOL,**fields(h),'state':'ACCEPTED','stage':'CONFIGURATION_RESTORE','acceptedAt':time.time()};b.atomic(d/'receipt.json',receipt)
    b.run(['systemd-run','--quiet','--no-block','--unit=eatwhat-member-recovery-'+h['id'],'--property=Type=exec','--property=UMask=0077','--property=RuntimeMaxSec=300','--property=StandardOutput=null','--property=StandardError=null','/usr/bin/python3',str(d/'agent.py'),'run',str(d/'input.json')],timeout=15)
    return receipt
def probe(h,loaded):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs):return None
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    def request(path):
        req=urllib.request.Request('http://127.0.0.1:'+str(loaded['httpPort'])+path,headers={'X-Control-Node-Token':loaded['nodeToken']} if path.startswith('/internal/') else {})
        with opener.open(req,timeout=3) as response:
            raw=response.read(262145);require(response.status==200 and len(raw)<=262144);return json.loads(raw)
    require(request('/actuator/health/readiness').get('status')=='UP')
    snapshot=request('/internal/control/v1/snapshot');require(snapshot.get('observationStatus')=='OBSERVED')
    require(snapshot.get('siteId')==h['siteId'] and snapshot.get('instanceId')==h['instanceId'] and snapshot.get('mode')=='EXTERNAL')
    require(snapshot.get('database',{}).get('databaseFingerprint')==loaded['before']['fingerprint'])
def perform(h):
    m=member();r=m.recovery();b=r.bootstrap();root=validate(h);d=directory(h);receipt=status(h,False)
    if receipt['state']!='ACCEPTED':return receipt
    locks=[]
    try:
        for path in [b.BASE/'.project-locks'/('eatwhat-site-'+h['serverId'][:8]+'.lock'),root/'installation.lock']:
            r.read_file(path,4096,{0o600},{r.ROOT_UID});fd=os.open(path,os.O_WRONLY|os.O_NOFOLLOW);locks.append(fd);fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        loaded=load(h,False);require(loaded['freezeHash']==h['freezeHash'])
        b.atomic(d/'before.properties',loaded['currentProperties']);b.atomic(d/'database-identity.json',loaded['before'])
        receipt.update(effectsStarted=True);b.atomic(d/'receipt.json',receipt)
        b.run(m.compose(root)+['stop','app'],timeout=30)
        b.atomic(root/'config/application.properties',loaded['properties']);os.chown(root/'config/application.properties',r.APP_UID,r.APP_GID)
        for name,expected in loaded['authFiles'].items():
            if expected is not None:
                path=root/'config'/name;require(m.digest(read(path,65536))==expected);path.unlink()
        b.run(m.compose(root)+['up','-d','--no-deps','--force-recreate','app'],timeout=60)
        m.await_runtime(lambda:probe(h,loaded))
        require(m.identity(root,loaded['environment'])==loaded['before'])
        require(read(root/'config/application.properties')==loaded['properties'])
        receipt.update(state='RESTORED',stage='VERIFIED',databaseFingerprint=loaded['before']['fingerprint'],originalDatabasePreserved=True,clusterMembership='NOT_JOINED',newEnrollmentPlanRequired=True)
    except Exception:
        receipt.update(state='FAILED',code='MEMBER_RECOVERY_FAILED_PRESERVED')
    finally:
        try:b.atomic(d/'receipt.json',receipt)
        finally:
            for fd in reversed(locks):os.close(fd)
    return receipt
def main():
    try:
        action=sys.argv[1];require(action in ['inspect','dispatch','status','run'])
        h=json.loads(read(Path(sys.argv[2]))) if action=='run' else member().recovery().bootstrap().input_header()
        print(json.dumps({'inspect':inspect,'dispatch':dispatch,'status':status,'run':perform}[action](h),separators=(',',':')))
    except Exception:print(json.dumps({'protocol':PROTOCOL,'state':'REJECTED','code':'MEMBER_RECOVERY_PRECONDITION_FAILED'}));sys.exit(1)
if __name__=='__main__':main()
