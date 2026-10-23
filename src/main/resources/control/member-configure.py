"""Fixed EXTERNAL activation, before genuine JOIN. No database identities or membership rows are written.
The reviewed installation/recovery helpers are embedded by the controller, never read from a caller path.
"""
import base64, datetime, fcntl, hashlib, json, os, re, shutil, stat, sys, tempfile, time, types, urllib.error, urllib.request, uuid, zipfile
from pathlib import Path

RECOVERY_SOURCE='__EATWHAT_RECOVERY_BASE64__'
_RECOVERY=None
PROTOCOL='eatwhat-member-configure-v1'
BOOTSTRAP_INGRESS_BASE=Path('/var/lib/eatwhat-controller-bootstrap-member-ingress')
class Rejected(Exception):pass
def require(value):
    if not value:raise Rejected()
def recovery():
    global _RECOVERY
    if _RECOVERY is None:
        m=types.ModuleType('reviewed_installation_helpers');exec(compile(base64.b64decode(RECOVERY_SOURCE),'<reviewed-recovery>','exec'),m.__dict__);_RECOVERY=m
    return _RECOVERY
def digest(value):return hashlib.sha256(value).hexdigest()
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':')).encode()
def directory_check(path,owners):
    require(path.is_dir() and not path.is_symlink() and path.stat().st_uid in owners and stat.S_IMODE(path.stat().st_mode)==0o700)
def validated(h):
    require(os.geteuid()==0 and sys.platform=='linux')
    for key in ['id','serverId','instanceId','clusterId']:require(str(uuid.UUID(h[key]))==h[key] and uuid.UUID(h[key]).int!=0)
    require(isinstance(h['siteId'],str) and re.fullmatch(r'server-[0-9a-f]{12}',h['siteId']))
    require(type(h['allocationSlot']) is int and 1<=h['allocationSlot']<=1023)
    require(re.fullmatch('[0-9a-f]{64}',h['artifactSha256']))
    r=recovery();root=r.bootstrap().safe_root(h['serverId'])
    directory_check(root,{r.ROOT_UID})
    for name in ['config','deploy','releases','private','app-state']:
        directory_check(root/name,{r.ROOT_UID,r.APP_UID})
    return root
def read(path,limit=262144):
    r=recovery();return r.read_file(path,limit,{0o600},{r.ROOT_UID,r.APP_UID})
def compose(root):return ['docker','compose','--project-name','eatwhat-site-'+root.name[:8],'--env-file',str(root/'deploy/.env'),'-f',str(root/'deploy/compose.json')]
def sql(root,env,statement):return recovery().sql(compose(root),env,'eatwhat',statement)
def identity(root,env):
    return json.loads(sql(root,env,"""SELECT json_build_object(
      'node',(SELECT row_to_json(n) FROM eatwhat_identity.node_state n WHERE singleton),
      'allocation',(SELECT row_to_json(a) FROM eatwhat_identity.allocation a WHERE singleton),
      'fingerprint',(SELECT md5(cluster_identifier||':'||database_oid::text) FROM eatwhat_identity.node_state WHERE singleton),
      'members',(SELECT count(*) FROM eatwhat_cluster.member),
      'changes',(SELECT count(*) FROM eatwhat_cluster.local_change),
      'localStates',(SELECT count(*) FROM eatwhat_cluster.local_state),
      'users',(SELECT count(*) FROM public.users),
      'outbox',(SELECT count(*) FROM eatwhat_sync.outbox),
      'inbox',(SELECT count(*) FROM eatwhat_sync.inbox))::text"""))
def runtime_capable(path,private_ca=False):
    with zipfile.ZipFile(path) as jar:
        require(jar.read('META-INF/eatwhat-member-configuration-v1')==b'eatwhat-member-configuration-v1\n')
        require('BOOT-INF/classes/you/v50to/eatwhat/cluster/ClusterRouting.class' in jar.namelist())
        if private_ca:require(jar.read('META-INF/eatwhat-node-private-ca-v1')==b'eatwhat-node-private-ca-v1\n')

def runtime(h):
    value=h['runtime'];require(isinstance(value,dict))
    keys={'profileId','properties','authPassword','authCaPem','nodeToken'}
    terminated='authTransport' in value
    require(set(value)==keys|({'authTransport'} if terminated else set()))
    if terminated:require(value['authTransport']=='TLS_TERMINATED')
    p=value['properties'];require(isinstance(p,dict) and len(p)<=64 and all(isinstance(k,str) and isinstance(v,str) and len(v)<=2048 and not any(ord(c)<32 for c in v) for k,v in p.items()))
    expected={'app.site.cluster-id':h['clusterId'],'app.site.id':h['siteId'],'app.site.instance-id':h['instanceId'],'app.site.mode':'EXTERNAL','app.site.allocation-slot':str(h['allocationSlot']),
      'app.control.enrollment-required':'true','app.cluster.enabled':'true','app.cluster.coordinator':'false','app.cluster.single-site-only':'false','app.cluster.control-lease.enabled':'false','app.cluster.automatic-gc-enabled':'false',
      'app.cluster.auth-store.enabled':'true','app.cluster.auth-store.password-file':'/config/authority-auth.password',
      'app.account.enabled':'true','app.account.synthetic-only':'false','app.sync.transport-enabled':'true','app.sync.synthetic-only':'false','app.sync.capture-enabled':'false',
      'app.storage.files.lifecycle-enabled':'true','app.storage.files.replication.enabled':'true','app.storage.files.replication.worker-enabled':'false','app.storage.files.gc.execute-enabled':'false','app.control.deployment.enabled':'false'}
    for domain in ['activity','relation','profile','privacy','notification','catalog','files']:expected['app.sync.'+domain+'.enabled']='true'
    dynamic={'app.cluster.authority-origin','app.cluster.authority-api-prefix','app.cluster.authority-site-id','app.cluster.authority-instance-id','app.cluster.auth-store.jdbc-url','app.cluster.auth-store.username'}
    if value.get('authCaPem'):
        expected['app.cluster.node-ca-file']='/config/authority-ca.pem';expected['app.control.node-ca-file']='/config/authority-ca.pem'
    require(set(p)==set(expected)|dynamic and all(p[k]==v for k,v in expected.items()))
    from urllib.parse import urlsplit
    origin=urlsplit(p['app.cluster.authority-origin']);require(origin.scheme=='https' or origin.scheme=='http' and origin.hostname in ['127.0.0.1','::1','localhost']);require(origin.hostname and origin.path in ['', '/'] and not origin.username and not origin.password and not origin.query and not origin.fragment)
    prefix=p['app.cluster.authority-api-prefix'];require(prefix=='' or re.fullmatch(r'(?:/[A-Za-z0-9_-]+){1,8}',prefix) and len(prefix)<=128)
    require(re.fullmatch('[A-Za-z0-9][A-Za-z0-9_-]{0,63}',p['app.cluster.authority-site-id']) and str(uuid.UUID(p['app.cluster.authority-instance-id']))==p['app.cluster.authority-instance-id'])
    require(re.fullmatch('[A-Za-z_][A-Za-z0-9_]{0,62}',p['app.cluster.auth-store.username']))
    jdbc=p['app.cluster.auth-store.jdbc-url'];require(jdbc.startswith('jdbc:postgresql://'));db=urlsplit(jdbc[5:]);require(db.hostname and not db.username and not db.password and not db.fragment and re.fullmatch('/[A-Za-z_][A-Za-z0-9_]{0,62}',db.path) and db.port!=0)
    bounded='connectTimeout=3&socketTimeout=5';verified=bounded+'&sslmode=verify-full&sslrootcert=/config/authority-ca.pem'
    if terminated:require(db.query==verified+'&channelBinding=disable')
    else:require((db.hostname in ['127.0.0.1','localhost','::1'] and db.query==bounded) or db.query==verified)
    require(isinstance(value['authPassword'],str) and 0<len(value['authPassword'])<=4096 and not any(ord(c)<32 for c in value['authPassword']))
    require(isinstance(value['nodeToken'],str) and re.fullmatch('[A-Za-z0-9_-]{43,128}',value['nodeToken']))
    require(isinstance(value['authCaPem'],str) and len(value['authCaPem'])<=65536 and 'PRIVATE KEY' not in value['authCaPem'])
    if terminated or db.hostname not in ['127.0.0.1','localhost','::1']:require('-----BEGIN CERTIFICATE-----' in value['authCaPem'])
    return p

def bootstrap_guard(h):
    """Optional composite route: only the original installed member and relay.
    This adds no URL/path supplied by HTTP and never edits the controller bundle.
    """
    if 'bootstrapContext' not in h:return
    ctx=h['bootstrapContext'];require(isinstance(ctx,dict) and set(ctx) in [{'bootstrapId','ingressId','ingressBinding','ingressAgentSha256'},{'bootstrapId','ingressId','ingressBinding','ingressAgentSha256','baseEpoch'}])
    if 'baseEpoch' in ctx:require(type(ctx['baseEpoch']) is int and 0<ctx['baseEpoch']<2**63-1)
    phase=lambda name:str(uuid.UUID(bytes=hashlib.md5((ctx['bootstrapId']+':'+name).encode()).digest(),version=3))
    require(str(uuid.UUID(ctx['bootstrapId']))==ctx['bootstrapId'] and uuid.UUID(ctx['bootstrapId']).int!=0)
    require(h['id']==phase('MEMBER_RUNTIME') and ctx['ingressId']==phase('MEMBER_HTTPS_INGRESS'))
    for key in ['ingressBinding','ingressAgentSha256']:require(re.fullmatch('[a-f0-9]{64}',ctx[key]))
    require(re.fullmatch('[a-f0-9]{64}',h['approvalDigest']) and re.fullmatch('[a-f0-9]{64}',h['agentSha256']))
    r=recovery();base=BOOTSTRAP_INGRESS_BASE/ctx['ingressId'];directory_check(base,{r.ROOT_UID})
    raw=r.read_file(base/'input.json',65536,{0o600},{r.ROOT_UID});code=r.read_file(base/'agent.py',524288,{0o600},{r.ROOT_UID});prior=json.loads(raw)
    require(digest(json.dumps(prior,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode())==ctx['ingressBinding']
        and digest(code)==ctx['ingressAgentSha256']==prior['agentSha256']
        and prior['id']==ctx['ingressId'] and prior['bootstrapId']==ctx['bootstrapId']
        and prior['serverId']==h['serverId'] and prior['approvalDigest']==h['approvalDigest'])
    ingress=types.ModuleType('original_member_configuration_ingress');exec(compile(code,'<original-ingress>','exec'),ingress.__dict__)
    require(ingress.status(prior)['state']=='HTTPS_READY')
    relay,relay_input,relay_state=ingress.original_relay(prior)
    business,business_input,live=relay.original_business(relay_input,False);installed=business_input['business']
    for key in ['serverId','siteId','instanceId','clusterId','allocationSlot','artifactSha256']:require(installed[key]==h[key])
    require(h['runtime']['nodeToken']==installed['nodeToken'])
    config=relay_state['config'];p=runtime(h)
    _,controller=business.controller(business_input,False);bundle=controller['bundle']
    profiles=json.loads(base64.b64decode(bundle['privateFiles']['cluster-profiles.json'],validate=True))
    authorities=[p for p in profiles.values() if p.get('siteId')==bundle['authoritySiteId']]
    require(len(authorities)==1);authority=authorities[0]
    require(authority.get('instanceId')==bundle['authorityInstanceId'] and authority.get('mode')=='CAMPUS' and authority.get('allocationSlot')==0)
    require(h['runtime'].get('authTransport')=='TLS_TERMINATED'
        and p['app.cluster.authority-origin']=='https://'+config['listenAddress']+':28443'
        and p['app.cluster.authority-api-prefix']==authority.get('apiPrefix','')
        and p['app.cluster.authority-site-id']==bundle['authoritySiteId']
        and p['app.cluster.authority-instance-id']==bundle['authorityInstanceId']
        and p['app.cluster.auth-store.username']==config['databaseUsername']
        and p['app.cluster.auth-store.jdbc-url']=='jdbc:postgresql://'+config['listenAddress']+':25443/'+config['databaseName']
            +'?connectTimeout=3&socketTimeout=5&sslmode=verify-full&sslrootcert=/config/authority-ca.pem&channelBinding=disable')
    require(h['runtime']['authCaPem'].encode()==relay.private(relay.folder(relay_input)/'ca.pem'))

def load(h,locks=True):
    root=validated(h);r=recovery();b=r.bootstrap();p=runtime(h)
    bootstrap_guard(h)
    if locks:
        r.assert_idle(root/'installation.lock');r.assert_idle(b.BASE/'.project-locks'/('eatwhat-site-'+h['serverId'][:8]+'.lock'))
    for name in ['authority-auth.password','authority-ca.pem']:require(not (root/'config'/name).exists() and not (root/'config'/name).is_symlink())
    source=json.loads(read(root/'installation.json'));require(source.get('state')=='SUCCEEDED' and source.get('clusterMembership')=='NOT_JOINED')
    for key in ['serverId','siteId','instanceId','clusterId','allocationSlot','artifactSha256']:require(source.get(key)==h[key])
    require(source.get('mode')=='EXTERNAL' and isinstance(source.get('restoreEvidenceId'),str))
    require(r.digest(root/'releases/current.jar')==h['artifactSha256'])
    runtime_capable(root/'releases/current.jar',bool(h['runtime']['authCaPem']))
    raw=read(root/'config/application.properties');old=r.properties(raw)
    for key in ['app.site.cluster-id','app.site.id','app.site.instance-id','app.site.mode','app.site.allocation-slot']:require(old.get(key)==p[key])
    require(old.get('app.cluster.enabled')=='false' and old.get('app.control.enrollment-required')=='true')
    require(read(root/'config/control.token',256).decode().strip()==h['runtime']['nodeToken'])
    document=json.loads(read(root/'deploy/compose.json'));port,images,env=r.validate_compose(root,document)
    loaded={'root':root,'compose':document,'images':images};r.preflight(loaded,h)
    before=identity(root,env);require(before['node']['site_id']==h['siteId'] and before['node']['instance_id']==h['instanceId'] and before['node']['mode']=='EXTERNAL')
    require(all(before['allocation'].get(k)==v for k,v in {'cluster_id':h['clusterId'],'site_id':h['siteId'],'instance_id':h['instanceId'],'slot':h['allocationSlot']}.items()))
    require(all(before[k]==0 for k in ['members','changes','localStates','users','outbox','inbox']))
    originals={name:read(root/name) for name in ['deploy/compose.json','deploy/.env','installation.json']}
    proof={'properties':digest(raw),'compose':digest(originals['deploy/compose.json']),'installation':digest(originals['installation.json']),'jar':h['artifactSha256'],'database':before,'runtime':h['runtime']}
    if 'bootstrapContext' in h:proof['originalFiles']={k:digest(v) for k,v in originals.items()}
    return {'root':root,'environment':env,'before':before,'properties':raw,'originals':originals,'httpPort':port,'freezeHash':digest(canonical(proof))}
def inspect(h):
    loaded=load(h);return {'protocol':PROTOCOL,'id':h['id'],'serverId':h['serverId'],'ready':True,'freezeHash':loaded['freezeHash'],'databaseFingerprint':loaded['before']['fingerprint']}
def directory(h,create=False):
    root=validated(h);p=root/'private/member-enrollments'
    if create:p.mkdir(mode=0o700,exist_ok=True)
    if p.exists() or p.is_symlink():directory_check(p,{recovery().ROOT_UID})
    p=p/h['id']
    if create:p.mkdir(mode=0o700)
    if p.exists() or p.is_symlink():directory_check(p,{recovery().ROOT_UID})
    return p
def status(h,observe_worker=True):
    p=directory(h)/'receipt.json'
    if not p.exists():return {'protocol':PROTOCOL,'id':h['id'],'serverId':h['serverId'],'state':'NOT_STARTED'}
    result=json.loads(read(p));require(all(result.get(k)==h[k] for k in ['id','serverId','siteId','instanceId','clusterId','allocationSlot','artifactSha256']))
    require(json.loads(read(directory(h)/'input.json',524288))=={k:v for k,v in h.items() if k!='agent'})
    if observe_worker and result.get('state')=='ACCEPTED' and time.time()-result.get('acceptedAt',0)>15:
        try:
            state=recovery().bootstrap().run(['systemctl','show','--property=ActiveState','--value','eatwhat-member-'+h['id']],timeout=5).decode().strip()
            if state in ['inactive','failed']:result.update(state='OUTCOME_UNKNOWN',code='MEMBER_CONFIGURATION_WORKER_NOT_RUNNING')
        except Exception:pass  # Failure to observe a process is not proof it stopped.
    return result
def dispatch(h):
    prior=status(h)
    if prior['state']!='NOT_STARTED':return prior
    try:require(inspect(h)['freezeHash']==h['freezeHash'])
    except Exception:return {'protocol':PROTOCOL,'id':h['id'],'serverId':h['serverId'],'state':'REJECTED','effectsStarted':False}
    agent=h.pop('agent');require(isinstance(agent,str) and len(agent)<=262144)
    if 'bootstrapContext' in h:require(digest(agent.encode())==h['agentSha256'])
    d=directory(h,True);b=recovery().bootstrap();b.atomic(d/'input.json',h);b.atomic(d/'agent.py',agent.encode())
    result={'protocol':PROTOCOL,**{k:h[k] for k in ['id','serverId','siteId','instanceId','clusterId','allocationSlot','artifactSha256']},'state':'ACCEPTED','stage':'CONFIGURATION','acceptedAt':time.time()};b.atomic(d/'receipt.json',result)
    b.run(['systemd-run','--quiet','--no-block','--unit=eatwhat-member-'+h['id'],'--property=Type=exec','--property=UMask=0077','--property=RuntimeMaxSec=300','--property=StandardOutput=null','--property=StandardError=null','/usr/bin/python3',str(d/'agent.py'),'run',str(d/'input.json')],timeout=15)
    return result
def edited(raw,values):
    def escape(value):
        result=''
        raw=str(value).encode('utf-16-be',errors='surrogatepass')
        for i in range(0,len(raw),2):
            code=int.from_bytes(raw[i:i+2],'big');char=chr(code)
            result+=('\\u%04x'%code) if code<32 or code>126 else ('\\'+char if char in '\\=:# !' else char)
        return result
    old=recovery().properties(raw)
    old={k:v for k,v in old.items() if not re.fullmatch(r'app\.(?:account|sync(?:\.[a-z]+)?)\.peer-.*',k)}
    old.update(values);return ''.join(escape(k)+'='+escape(v)+'\n' for k,v in sorted(old.items())).encode('ISO-8859-1')
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,request,fp,code,message,headers,newurl):return None
def probe(h,loaded,membership=None):
    req=urllib.request.Request('http://127.0.0.1:'+str(loaded['httpPort'])+'/internal/cluster/v1/enrollment-proof',headers={'X-Control-Node-Token':h['runtime']['nodeToken']})
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect()).open(req,timeout=3) as response:
            require(response.status==200);data=response.read(262145);require(len(data)<=262144);result=json.loads(data)
    except urllib.error.HTTPError as failure:failure.close();raise Rejected() from None
    require(all(result.get(k)==h[k] for k in ['siteId','instanceId','clusterId','allocationSlot']))
    require(result.get('mode')=='EXTERNAL' and result.get('protocolVersion')==1 and result.get('dataPlaneReady') is True and result.get('databaseFingerprint')==loaded['before']['fingerprint'])
    require(result.get('authStoreBinding')=='VERIFIED')
    require(result.get('membership')==({'epoch':0,'state':'UNJOINED'} if membership is None else membership))
    return result
def await_runtime(check,seconds=90):
    # Bound elapsed time, not attempts: a timed-out probe can consume three
    # seconds itself. Attempts * probe timeout must not outrun the worker limit.
    deadline=time.monotonic()+seconds
    while True:
        try:check();return
        except Exception:
            remaining=deadline-time.monotonic()
            if remaining<=0:raise Rejected()
            time.sleep(min(1,remaining))

def configured_files(h):
    """Exact original configuration evidence, independent of subsequent JOIN."""
    receipt=status(h);require(receipt['state']=='CONFIGURED');root=validated(h);r=recovery();runtime(h);bootstrap_guard(h)
    original=json.loads(read(directory(h)/'input.json',524288));require(original=={k:v for k,v in h.items() if k!='agent'})
    before=json.loads(read(directory(h)/'database-identity.json'));raw=read(directory(h)/'original.properties')
    for source,saved in [('deploy/compose.json','original-compose.json'),('deploy/.env','original.env'),('installation.json','original-installation.json')]:
        require(read(root/source)==read(directory(h)/saved))
    require(read(root/'config/application.properties')==edited(raw,runtime(h)) and read(root/'config/control.token',256).decode().strip()==h['runtime']['nodeToken'])
    for name,key in [('authority-auth.password','authPassword'),('authority-ca.pem','authCaPem')]:
        if h['runtime'][key]:require(read(root/'config'/name)==h['runtime'][key].encode())
    require(r.digest(root/'releases/current.jar')==h['artifactSha256'])
    document=json.loads(read(root/'deploy/compose.json'));port,images,env=r.validate_compose(root,document);r.preflight({'root':root,'compose':document,'images':images},h)
    return receipt,root,env,port,before
def verify_configured(h):
    """Fresh evidence after a saved CONFIGURED receipt; never repairs or starts work."""
    receipt,root,env,port,before=configured_files(h)
    after=identity(root,env);require(after==before)
    require(all(after[k]==0 for k in ['members','changes','localStates','users','outbox','inbox']))
    probe(h,{'httpPort':port,'before':before})
    return {**receipt,'configurationRevalidated':True,'authStoreVerified':True,'clusterMembership':'NOT_JOINED','joinAuthorized':False}

def member(value):
    require(isinstance(value,dict) and set(value)=={'siteId','instanceId','mode','allocationSlot'})
    require(isinstance(value['siteId'],str) and re.fullmatch('[A-Za-z0-9_-]{1,64}',value['siteId']))
    require(isinstance(value['instanceId'],str) and str(uuid.UUID(value['instanceId']))==value['instanceId'] and uuid.UUID(value['instanceId']).int!=0)
    require(type(value['allocationSlot']) is int and 0<=value['allocationSlot']<=1023
        and (value['mode']=='CAMPUS' and value['allocationSlot']==0 or value['mode']=='EXTERNAL' and value['allocationSlot']>0))
    # Match the reviewed Java record's serialization order for phase hashes.
    return {key:value[key] for key in ['siteId','instanceId','mode','allocationSlot']}
def joined_expectation(h,expected):
    ctx=h.get('bootstrapContext');require(isinstance(ctx,dict) and type(ctx.get('baseEpoch')) is int and ctx['baseEpoch']>0)
    require(isinstance(expected,dict) and set(expected)=={'bootstrapId','changeId','baseEpoch','epoch','databaseFingerprint','members','membershipBinding'})
    phase=lambda name:str(uuid.UUID(bytes=hashlib.md5((ctx['bootstrapId']+':'+name).encode()).digest(),version=3))
    require(expected['bootstrapId']==ctx['bootstrapId'] and expected['changeId']==phase('MEMBER_JOIN') and h['id']==phase('MEMBER_RUNTIME'))
    require(type(expected['baseEpoch']) is int and expected['baseEpoch']==ctx['baseEpoch'] and type(expected['epoch']) is int and expected['epoch']==ctx['baseEpoch']+1)
    require(isinstance(expected['membershipBinding'],str) and re.fullmatch('[A-Za-z0-9+/]{43}=',expected['membershipBinding'])
        and expected['databaseFingerprint']==h['databaseFingerprint'] and re.fullmatch('[0-9a-f]{32}',h['databaseFingerprint']))
    require(isinstance(expected['members'],list) and 2<=len(expected['members'])<=16)
    members=[member(value) for value in expected['members']]
    for key in ['siteId','instanceId','allocationSlot']:require(len({value[key] for value in members})==len(members))
    local=member({'siteId':h['siteId'],'instanceId':h['instanceId'],'mode':'EXTERNAL','allocationSlot':h['allocationSlot']})
    properties=h['runtime']['properties'];authority=member({'siteId':properties['app.cluster.authority-site-id'],'instanceId':properties['app.cluster.authority-instance-id'],'mode':'CAMPUS','allocationSlot':0})
    require(local in members and authority in members)
    return members,local
def membership_proof(h,expected,observed):
    members,local=joined_expectation(h,expected)
    require(isinstance(observed,dict) and set(observed)=={'state','change','phases'})
    state=observed['state'];require(isinstance(state,dict) and state=={'cluster_id':h['clusterId'],'site_id':h['siteId'],'instance_id':h['instanceId'],
        'epoch':expected['epoch'],'state':'ACTIVE','pending_change':None,'members':members} and type(state['epoch']) is int)
    saved=observed['change'];require(isinstance(saved,dict) and set(saved)=={'change_id','plan','plan_hash'} and saved['change_id']==expected['changeId'])
    plan={'clusterId':h['clusterId'],'changeId':expected['changeId'],'baseEpoch':expected['baseEpoch'],'nextEpoch':expected['epoch'],
        'kind':'JOIN','siteId':h['siteId'],'participants':members,'nextMembers':members}
    require(saved['plan']==plan and type(saved['plan'].get('baseEpoch')) is int and type(saved['plan'].get('nextEpoch')) is int)
    encode=lambda value:json.dumps(value,separators=(',',':'),ensure_ascii=False).encode('utf-8')
    plan_hash=digest(encode(plan));require(saved['plan_hash']==plan_hash)
    phases=observed['phases'];require(isinstance(phases,list) and len(phases)==5 and all(isinstance(value,dict) and set(value)=={'phase','proof_hash'} for value in phases))
    require({value['phase'] for value in phases}=={'STAGED','FENCED','PREPARED','APPLIED','RELEASED'})
    for value in phases:require(value['proof_hash']==digest(encode([plan,local,value['phase']])))
    return plan_hash
def joined_membership(root,env,expected):
    identifier=expected['changeId'];require(str(uuid.UUID(identifier))==identifier and uuid.UUID(identifier).int!=0)
    # The only interpolated SQL value is the previously validated canonical UUID.
    return json.loads(sql(root,env,"""SELECT json_build_object(
      'state',(SELECT json_build_object('cluster_id',cluster_id,'site_id',site_id,'instance_id',instance_id,'epoch',epoch,'state',state,'pending_change',pending_change,'members',members) FROM eatwhat_cluster.local_state WHERE singleton),
      'change',(SELECT json_build_object('change_id',change_id,'plan',plan,'plan_hash',plan_hash) FROM eatwhat_cluster.local_change WHERE change_id='%s'::uuid),
      'phases',coalesce((SELECT json_agg(json_build_object('phase',phase,'proof_hash',proof_hash) ORDER BY phase) FROM eatwhat_cluster.local_phase WHERE change_id='%s'::uuid),'[]'::json))::text"""%(identifier,identifier)))
def health_joined(h,port,expected):
    base='http://127.0.0.1:'+str(port);opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    def call(path,body=None):
        request=urllib.request.Request(base+path,data=None if body is None else canonical(body),headers={'X-Control-Node-Token':h['runtime']['nodeToken'],'Accept':'application/json',**({} if body is None else {'Content-Type':'application/json'})})
        try:
            with opener.open(request,timeout=3) as response:
                require(response.status==200 and response.headers.get_content_type()=='application/json');raw=response.read(262145);require(len(raw)<=262144);return json.loads(raw)
        except urllib.error.HTTPError as failure:failure.close();raise Rejected() from None
    def verified():
        value=call('/internal/cluster/v1/health');require(value.get('siteId')==h['siteId'] and value.get('instanceId')==h['instanceId'] and value.get('clusterId')==h['clusterId'])
        control=value['control'];require(control.get('siteId')==h['siteId'] and control.get('instanceId')==h['instanceId'] and control.get('mode')=='EXTERNAL'
            and control.get('readiness')=='READY' and control.get('storageReachability')=='REACHABLE')
        checks=control['healthChecks'];require(isinstance(checks,dict) and set(checks)=={'application','database','redis','storage'})
        kinds={'application':'APPLICATION_ACCEPTING_TRAFFIC','database':'POSTGRES_SELECT_1','redis':'REDIS_PING','storage':'S3_HEAD_BUCKET_AND_EMPTY_LIST'}
        now=datetime.datetime.now(datetime.timezone.utc);times=[]
        for name,kind in kinds.items():
            check=checks[name];require(check.get('status')=='PASS' and check.get('code')=='OK' and check.get('running') is False and check.get('checkKind')==kind)
            stamp=lambda key:datetime.datetime.fromisoformat(check[key].replace('Z','+00:00'))
            checked=stamp('checkedAt');until=stamp('validUntil');require(checked.tzinfo is not None and until.tzinfo is not None
                and now-datetime.timedelta(seconds=10)<=checked<=now+datetime.timedelta(seconds=2) and now<until<=checked+datetime.timedelta(seconds=60))
            times.append(checked)
        require(call('/actuator/health/readiness').get('status')=='UP')
        return min(times).isoformat()
    request_id=str(uuid.UUID(bytes=hashlib.md5((expected['bootstrapId']+':VERIFY').encode()).digest(),version=3))
    call('/internal/cluster/v1/health-check',{'requestId':request_id})
    result={}
    def check():result['checkedAt']=verified()
    await_runtime(check,15);return result['checkedAt']
def verify_joined(request):
    require(isinstance(request,dict) and set(request)=={'original','joinProof'});h=request['original'];expected=request['joinProof'];joined_expectation(h,expected)
    receipt,root,env,port,before=configured_files(h);after=identity(root,env)
    require(after['node']==before['node'] and after['allocation']==before['allocation'] and after['fingerprint']==before['fingerprint']==expected['databaseFingerprint']
        and after['members']==before['members'] and after['localStates']==1 and after['changes']==before['changes']+1)
    plan_hash=membership_proof(h,expected,joined_membership(root,env,expected))
    probe(h,{'httpPort':port,'before':before},{'epoch':expected['epoch'],'state':'ACTIVE','pending_change':None})
    checked=health_joined(h,port,expected)
    # Repeat original file/services validation after slow dependency probes.
    configured_files(h)
    checked=health_joined(h,port,expected)
    require(plan_hash==membership_proof(h,expected,joined_membership(root,env,expected)))
    return {'protocol':'eatwhat-member-joined-runtime-v1','id':h['id'],'serverId':h['serverId'],'siteId':h['siteId'],'instanceId':h['instanceId'],
        'clusterId':h['clusterId'],'allocationSlot':h['allocationSlot'],'artifactSha256':h['artifactSha256'],'databaseFingerprint':expected['databaseFingerprint'],
        'bootstrapId':expected['bootstrapId'],'changeId':expected['changeId'],'epoch':expected['epoch'],'membershipBinding':expected['membershipBinding'],'planHash':plan_hash,
        'state':'JOINED_VERIFIED','clusterMembership':'ACTIVE','configurationRevalidated':True,'authStoreVerified':True,'localMembershipVerified':True,'dataPreserved':True,
        'nativeServicesRevalidated':True,'networkBaselineRevalidated':True,'applicationReady':True,'databaseReady':True,'redisReady':True,
        'storageReadCheckVerified':True,'storageWriteProbePerformed':False,'healthCheckedAt':checked,'joinAuthorized':False}

def perform(h):
    root=validated(h);r=recovery();b=r.bootstrap();d=directory(h);receipt=status(h,False)
    if receipt['state']!='ACCEPTED':return receipt
    locks=[]
    try:
        for path in [b.BASE/'.project-locks'/('eatwhat-site-'+h['serverId'][:8]+'.lock'),root/'installation.lock']:
            r.read_file(path,4096,{0o600},{r.ROOT_UID});fd=os.open(path,os.O_WRONLY|os.O_NOFOLLOW);locks.append(fd);fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        loaded=load(h,False);require(loaded['freezeHash']==h['freezeHash'])
        b.atomic(d/'original.properties',loaded['properties']);b.atomic(d/'database-identity.json',loaded['before'])
        for source,saved in [('deploy/compose.json','original-compose.json'),('deploy/.env','original.env'),('installation.json','original-installation.json')]:
            b.atomic(d/saved,loaded['originals'][source])
        # No Docker volumes or dependency services are recreated; only app is stopped/recreated.
        b.run(compose(root)+['stop','app'],timeout=30)
        for name,data in [('authority-auth.password',h['runtime']['authPassword']),('authority-ca.pem',h['runtime']['authCaPem'])]:
            if data:b.atomic(root/'config'/name,data.encode());os.chown(root/'config'/name,r.APP_UID,r.APP_GID)
        b.atomic(root/'config/application.properties',edited(loaded['properties'],runtime(h)));os.chown(root/'config/application.properties',r.APP_UID,r.APP_GID)
        receipt.update(configurationApplied=True);b.atomic(d/'receipt.json',receipt)
        b.run(compose(root)+['up','-d','--no-deps','--force-recreate','app'],timeout=60)
        await_runtime(lambda:probe(h,loaded))
        after=identity(root,loaded['environment']);require(after['node']==loaded['before']['node'] and after['allocation']==loaded['before']['allocation'] and after['fingerprint']==loaded['before']['fingerprint'])
        require(all(after[k]==0 for k in ['members','changes','localStates','users','outbox','inbox']))
        receipt.update(state='CONFIGURED',stage='WAITING_FOR_JOIN',databaseFingerprint=after['fingerprint'],dataPreserved=True,clusterMembership='NOT_JOINED')
    except Exception:
        # Preserve bytes, database and receipts for explicit reconciliation. Never silently
        # restore configuration after an ambiguous protocol attempt or mark a member ACTIVE.
        receipt.update(state='FAILED',code='MEMBER_CONFIGURATION_FAILED_PRESERVED')
    finally:
        b.atomic(d/'receipt.json',receipt)
        for fd in reversed(locks):os.close(fd)
    return receipt
def main():
    try:
        action=sys.argv[1];require(action in ['inspect','dispatch','status','run','verify','verify-joined'])
        h=json.loads(read(Path(sys.argv[2]))) if action=='run' else recovery().bootstrap().input_header()
        result={'inspect':inspect,'dispatch':dispatch,'status':status,'run':perform,'verify':verify_configured,'verify-joined':verify_joined}[action](h);print(json.dumps(result,separators=(',',':')))
    except Exception:print(json.dumps({'protocol':PROTOCOL,'state':'REJECTED','code':'MEMBER_CONFIGURATION_PRECONDITION_FAILED'}));sys.exit(1)
if __name__=='__main__':main()
