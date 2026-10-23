"""Fixed, root-only first authority attachment. Never deletes or rewrites database identities."""
import base64, contextlib, fcntl, hashlib, json, os, pwd, re, shutil, socket, stat, subprocess, sys, tempfile, time, uuid
from pathlib import Path
CONTROL=Path('/opt/eatwhat-control'); BASE=Path('/opt/eatwhat-sites'); PORT=25432
ENV={'PATH':'/usr/local/bin:/usr/bin:/bin','LANG':'C.UTF-8','HOME':'/root','DOCKER_HOST':'unix:///var/run/docker.sock'}
class Rejected(Exception): pass
def require(value):
    if not value: raise Rejected()
def run(args,data=None,output=None,timeout=180):
    p=subprocess.run(args,input=data,stdin=None if data is not None else subprocess.DEVNULL,stdout=output or subprocess.PIPE,stderr=subprocess.DEVNULL,env=ENV,check=True,timeout=timeout)
    return p.stdout

def safe(path):
    require(not path.is_symlink())
    for parent in [path,*path.parents]:
        require(not parent.is_symlink())
    return path

def private(path):
    safe(path);require(path.is_file() and stat.S_IMODE(path.stat().st_mode)==0o600);return path

def digest(path):
    safe(path);require(path.is_file() and stat.S_IMODE(path.stat().st_mode)&0o027==0)
    with path.open('rb') as f: return hashlib.file_digest(f,'sha256').hexdigest()

def atomic(path,data,owner=None):
    safe(path);data=data if isinstance(data,bytes) else json.dumps(data,separators=(',',':')).encode()
    fd,name=tempfile.mkstemp(prefix='.authority-',dir=path.parent)
    try:
        os.fchmod(fd,0o600)
        if owner is not None: os.fchown(fd,owner,owner)
        with os.fdopen(fd,'wb') as f: f.write(data);f.flush();os.fsync(f.fileno())
        os.replace(name,path)
        fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
        try: os.fsync(fd)
        finally: os.close(fd)
    finally: Path(name).unlink(missing_ok=True)

def validated(h):
    require(os.geteuid()==0 and sys.platform=='linux')
    for key in ['id','serverId','instanceId','clusterId']: require(str(uuid.UUID(h[key]))==h[key])
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}',h['siteId']) is not None)
    require(str(int(h['_actor']))==h['_actor'] and int(h['_actor'])>0)
    require(isinstance(h['httpPort'],int) and 1024<=h['httpPort']<=65535 and h['httpPort'] not in [18080,18081,PORT])
    require(h['databasePort']==PORT)
    root=safe(BASE/h['serverId']);require(root.is_dir() and root.stat().st_uid==0 and stat.S_IMODE(root.stat().st_mode)==0o700)
    require(CONTROL.is_dir() and CONTROL.stat().st_uid==0 and stat.S_IMODE(CONTROL.stat().st_mode)&0o022==0)
    return root

def compose(root): return ['docker','compose','--project-directory',str(root),'--env-file',str(root/'deploy/.env'),'-f',str(root/'deploy/compose.json'),'-p','eatwhat-site-'+root.name[:8]]
def oldcompose(): return ['docker','compose','--project-directory',str(CONTROL/'infra'),'-f',str(CONTROL/'infra/compose.yml')]
def sql(cmd,statement):
    return run(cmd+['exec','-T','postgres','psql','-U','eatwhat_migration','-d','eatwhat','-v','ON_ERROR_STOP=1','-tAc',statement]).decode().strip()
def snapshot(cmd):
    return json.loads(sql(cmd,"""SELECT json_build_object(
      'node',(SELECT row_to_json(n) FROM eatwhat_identity.node_state n WHERE singleton),
      'allocation',(SELECT row_to_json(a) FROM eatwhat_identity.allocation a WHERE singleton),
      'state',(SELECT row_to_json(s) FROM eatwhat_cluster.state s WHERE singleton),
      'members',(SELECT count(*) FROM eatwhat_cluster.member),
      'changes',(SELECT count(*) FROM eatwhat_cluster.change),
      'outbox',(SELECT count(*) FROM eatwhat_sync.outbox),
      'inbox',(SELECT count(*) FROM eatwhat_sync.inbox),
      'consents',(SELECT count(*) FROM eatwhat_consent.subject),
      'handoffs',(SELECT count(*) FROM eatwhat_cluster.control_handoff WHERE state IN ('WAITING','TRANSFERRING')),
      'controllers',(SELECT count(*) FROM eatwhat_cluster.control_process WHERE expires_at>clock_timestamp()),
      'users',(SELECT json_agg(row_to_json(u)) FROM (SELECT id,nick_name,password_hash,banned,role FROM users ORDER BY id) u),
      'security',(SELECT coalesce(json_agg(row_to_json(v)),'[]') FROM eatwhat_cluster.session_security_version v),
      'credentials',(SELECT coalesce(json_agg(row_to_json(v)),'[]') FROM eatwhat_control.credential_version v),
      'credentialChanges',(SELECT coalesce(json_agg(row_to_json(v)),'[]') FROM eatwhat_control.credential_change v)
    )::text"""))

def prop(text,key):
    lines=[line.split('=',1)[1] for line in text.splitlines() if line.startswith(key+'=')]
    require(len(lines)==1);return lines[0].replace('\\:',':').replace('\\=', '=')
def edited(text,values):
    lines=[line for line in text.splitlines() if not any(line.startswith(k+'=') for k in values)]
    def esc(s): return str(s).replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r').replace('=','\\=').replace(':','\\:')
    return ('\n'.join(lines)+'\n'+''.join(k+'='+esc(v)+'\n' for k,v in values.items())).encode()

def inspect(h):
    root=validated(h);cp=private(CONTROL/'config/application.properties').read_text();bp=private(root/'config/application.properties').read_text()
    require(prop(cp,'app.control.servers.cluster-enrollment-enabled')=='true')
    require(prop(cp,'spring.datasource.url')=='jdbc:postgresql://127.0.0.1:15432/eatwhat')
    require(prop(cp,'app.site.cluster-id')==h['clusterId'])
    require(prop(bp,'app.site.id')==h['siteId'] and prop(bp,'app.site.instance-id')==h['instanceId'] and prop(bp,'app.site.mode')=='CAMPUS')
    require(prop(bp,'app.cluster.enabled')=='false')
    require(digest(CONTROL/'releases/eatwhat.jar')==digest(root/'releases/current.jar'))
    dc=json.loads(private(root/'deploy/compose.json').read_text());require(not dc['services']['postgres'].get('ports'))
    require(dc['services']['app']['ports']==['127.0.0.1:'+str(h['httpPort'])+':8080'])
    require(dc['services']['postgres']['environment']['PG_DB']=='eatwhat')
    a=snapshot(oldcompose());b=snapshot(compose(root))
    require(a['node'] and b['node'] and a['node']['site_id']!=b['node']['site_id'])
    require(b['node']['site_id']==h['siteId'] and b['node']['instance_id']==h['instanceId'] and b['node']['mode']=='CAMPUS')
    require(a['allocation']['cluster_id']==h['clusterId'])
    if b['allocation'] is not None:
        allocation=b['allocation']
        require(allocation.get('cluster_id')==h['clusterId'] and allocation.get('site_id')==h['siteId']
                and allocation.get('instance_id')==h['instanceId'] and allocation.get('slot')==0)
        require(prop(bp,'app.site.cluster-id')==h['clusterId'] and prop(bp,'app.site.allocation-slot')=='0'
                and prop(bp,'app.control.enrollment-required')=='true')
    require(a['state']['epoch']==0 and a['state']['cluster_id']==h['clusterId'] and b['state'] is None)
    for s in [a,b]:
        require(s['members']==s['changes']==s['outbox']==s['inbox']==s['consents']==s['handoffs']==0)
    require(a['controllers']==1 and b['controllers']==0)
    require(len(a['users'])==len(b['users'])==1 and a['users']==b['users'])
    require(str(a['users'][0]['id'])==h['_actor'] and a['users'][0]['role']=='admin' and not a['users'][0]['banned'])
    require(sql(oldcompose(),"SELECT count(*) FROM public.activity_foods")== '0')
    # Empty management authority only: no user/business data may be silently abandoned.
    tables=['activity_foods','activity_dinners','restaurants','foods','contacts','verifications','user_info','privacy','notifications','follow','likes']
    existing=json.loads(sql(oldcompose(),"SELECT coalesce(json_agg(tablename),'[]')::text FROM pg_tables WHERE schemaname='public'"))
    for table in tables:
        if table in existing: require(sql(oldcompose(),'SELECT count(*) FROM public."'+table+'"')=='0')
    with socket.socket() as sock: sock.bind(('127.0.0.1',PORT))
    # Volatile lease clocks are deliberately omitted; semantic database state and private configuration are frozen.
    proof={'control':hashlib.sha256(cp.encode()).hexdigest(),'business':hashlib.sha256(bp.encode()).hexdigest(),'compose':dc,'oldNode':a['node'],'newNode':b['node'],'oldAllocation':a['allocation'],'newAllocation':b['allocation'],'users':a['users'],'security':a['security'],'credentials':a['credentials'],'credentialChanges':a['credentialChanges'],'artifact':digest(CONTROL/'releases/eatwhat.jar')}
    return {'protocol':'eatwhat-authority-v1','ready':True,'preflightHash':hashlib.sha256(json.dumps(proof,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'administratorUsername':a['users'][0]['nick_name']}

def directory(h,create=False):
    parent=safe(CONTROL/'authority-jobs')
    if create: parent.mkdir(mode=0o700,exist_ok=True)
    if parent.exists(): require(parent.stat().st_uid==0 and stat.S_IMODE(parent.stat().st_mode)==0o700)
    d=safe(parent/h['id'])
    if create: d.mkdir(mode=0o700)
    if d.exists(): require(d.stat().st_uid==0 and stat.S_IMODE(d.stat().st_mode)==0o700)
    return d

def status(h):
    validated(h);d=directory(h)
    if not (d/'receipt.json').exists(): return {'id':h['id'],'serverId':h['serverId'],'state':'NOT_STARTED'}
    result=json.loads(private(d/'receipt.json').read_text());require(result['id']==h['id'] and result['serverId']==h['serverId']);return result

def dispatch(h):
    validated(h);prior=status(h)
    if prior['state']!='NOT_STARTED': return prior
    require(re.fullmatch('[0-9a-f]{64}',h['_preflightHash']) is not None)
    try: require(inspect(h)['preflightHash']==h['_preflightHash'])
    except Rejected: return {'id':h['id'],'serverId':h['serverId'],'state':'REJECTED','code':'AUTHORITY_PLAN_CHANGED','effectsStarted':False}
    agent=h.pop('agent');require(isinstance(agent,str) and len(agent)<100000)
    d=directory(h,True);atomic(d/'input.json',h);atomic(d/'agent.py',agent.encode())
    receipt={'id':h['id'],'serverId':h['serverId'],'state':'ACCEPTED'};atomic(d/'receipt.json',receipt)
    run(['systemd-run','--quiet','--no-block','--unit=eatwhat-authority-'+h['id'],'--property=Type=exec','--property=UMask=0077','--property=RuntimeMaxSec=600','--property=StandardOutput=null','--property=StandardError=null','/usr/bin/python3',str(d/'agent.py'),'run',str(d/'input.json')],timeout=15)
    return receipt

def wait(cmd):
    for _ in range(120):
        try:
            if run(cmd,timeout=3).strip()==b'1': return
        except Exception: pass
        time.sleep(1)
    raise Rejected()

def perform(h):
    # This independent unit survives stopping the controller's cgroup / its SSH client.
    time.sleep(4);root=validated(h);d=directory(h);lock=private(CONTROL/'state/deployment.lock').open('r+')
    fcntl.lockf(lock,fcntl.LOCK_EX)
    changed=False
    try:
        require(inspect(h)['preflightHash']==h['_preflightHash'])
        for path in (CONTROL/'state').glob('*.json'):
            if re.fullmatch(r'(deployment|server-job|server-cluster-job)-[0-9a-f-]{36}\.json',path.name):
                j=json.loads(private(path).read_text());require(j.get('id')==h['id'] or j.get('state') not in ['QUEUED','RUNNING','OUTCOME_UNKNOWN'])
        run(['systemctl','stop','eatwhat-control.service']);run(compose(root)+['stop','app'])
        # Fresh snapshots after writers stop; no role, sequence or node fingerprint is overwritten.
        a=snapshot(oldcompose());b=snapshot(compose(root));require(a['users']==b['users'] and len(a['users'])==1);original_node=b['node']
        for label,cmd in [('original-control',oldcompose()),('business',compose(root))]:
            with (d/(label+'.dump')).open('xb') as out:
                os.fchmod(out.fileno(),0o600);run(cmd+['exec','-T','postgres','pg_dump','-U','eatwhat_migration','-d','eatwhat','-Fc'],output=out,timeout=180);out.flush();os.fsync(out.fileno())
            require((d/(label+'.dump')).stat().st_size>1000)
        for source,name in [(CONTROL/'config/application.properties','control.properties'),(root/'config/application.properties','business.properties'),(root/'deploy/compose.json','compose.json'),(CONTROL/'config/cluster-profiles.json','cluster-profiles.json')]:
            if source.exists(): atomic(d/name,private(source).read_bytes())
        cp=(d/'control.properties').read_text();bp=(d/'business.properties').read_text();dc=json.loads((d/'compose.json').read_text())
        require(len(a['credentials'])<=1 and len(a['credentialChanges'])<=10000)
        for row in a['credentialChanges']:
            require(int(row['user_id'])==int(h['_actor']) and str(uuid.UUID(row['request_id']))==row['request_id'])
        if a['credentialChanges']:
            encoded=base64.b64encode(json.dumps(a['credentialChanges']).encode()).decode()
            sql(compose(root),"INSERT INTO eatwhat_control.credential_change SELECT * FROM jsonb_populate_recordset(NULL::eatwhat_control.credential_change,convert_from(decode('"+encoded+"','base64'),'UTF8')::jsonb) ON CONFLICT(request_id) DO NOTHING")
        # Preserve credential/session invalidation counters for the *identical* administrator.
        for table,rows in [('eatwhat_control.credential_version',a['credentials']),('eatwhat_cluster.session_security_version',a['security'])]:
            for row in rows:
                require(set(row)=={'user_id','version'} and int(row['user_id'])==int(h['_actor']) and int(row['version'])>=0)
                sql(compose(root),'INSERT INTO '+table+'(user_id,version) VALUES('+str(row['user_id'])+','+str(row['version'])+') ON CONFLICT(user_id) DO UPDATE SET version=greatest('+table+'.version,excluded.version)')
        changed=True
        dc['services']['postgres']['ports']=['127.0.0.1:'+str(PORT)+':5432'];atomic(root/'deploy/compose.json',dc)
        common={'app.site.cluster-id':h['clusterId'],'app.site.allocation-slot':0,'app.cluster.enabled':'true','app.cluster.coordinator':'true','app.cluster.single-site-only':'true','app.cluster.automatic-gc-enabled':'false','app.cluster.control-lease.enabled':'true','app.cluster.control-lease.duration-seconds':15,'app.account.enabled':'false','app.sync.capture-enabled':'false','app.sync.transport-enabled':'false'}
        atomic(root/'config/application.properties',edited(bp,common|{'app.cluster.control-lease.candidate':'false','app.cluster.control-lease.controller-name':'business-authority','app.cluster.profiles-file':'/config/cluster-profiles.json'}),10001)
        atomic(root/'config/cluster-profiles.json',{},10001)
        db=dc['services']['postgres']['environment'];runtime=pwd.getpwnam('eatwhat-control').pw_uid
        token=private(root/'config/control.token').read_bytes();atomic(CONTROL/'state/authority-node.token',token,runtime)
        profile={'authority':{'siteId':h['siteId'],'instanceId':h['instanceId'],'mode':'CAMPUS','allocationSlot':0,'origin':'http://127.0.0.1:'+str(h['httpPort']),'tokenFile':str(CONTROL/'state/authority-node.token'),'channels':{}}}
        atomic(CONTROL/'config/cluster-profiles.json',profile,runtime)
        jdbc='jdbc:postgresql://127.0.0.1:'+str(PORT)+'/eatwhat'
        values=common|{'app.site.id':h['siteId'],'app.site.instance-id':h['instanceId'],'spring.datasource.url':jdbc,'spring.datasource.username':db['PG_APP_USER'],'spring.datasource.password':db['PG_APP_PASSWORD'],'spring.flyway.url':jdbc,'spring.flyway.user':db['POSTGRES_USER'],'spring.flyway.password':db['POSTGRES_PASSWORD'],'app.cluster.control-lease.candidate':'true','app.cluster.profiles-file':str(CONTROL/'config/cluster-profiles.json')}
        atomic(CONTROL/'config/application.properties',edited(cp,values),runtime)
        # Existing installation.json is kept untouched as evidence of the original controller initialization.
        atomic(CONTROL/'config/authority-binding.json',{'jobId':h['id'],'clusterId':h['clusterId'],'siteId':h['siteId'],'instanceId':h['instanceId'],'serverId':h['serverId'],'databasePort':PORT,'originalDatabasePreserved':True,'singleSiteOnly':True},runtime)
        run(compose(root)+['up','-d','postgres','redis'],timeout=90)
        wait(compose(root)+['exec','-T','postgres','psql','-U','eatwhat_migration','-d','eatwhat','-tAc','SELECT 1'])
        run(compose(root)+['up','-d','--no-deps','--force-recreate','app'],timeout=60)
        # Migration callback performs the supported slot-0 allocation conversion, preserving node_state.
        for _ in range(120):
            try:
                import urllib.request
                with urllib.request.urlopen('http://127.0.0.1:'+str(h['httpPort'])+'/actuator/health/readiness',timeout=2) as r:
                    if r.status==200: break
            except Exception: pass
            time.sleep(1)
        else: raise Rejected()
        b=snapshot(compose(root));require(b['node']==original_node)
        require(b['allocation']['cluster_id']==h['clusterId'] and b['allocation']['slot']==0 and b['allocation']['site_id']==h['siteId'])
        require(b['users']==a['users'])
        atomic(d/'receipt.json',{'id':h['id'],'serverId':h['serverId'],'state':'SETUP_READY','siteId':h['siteId'],'instanceId':h['instanceId'],'clusterId':h['clusterId'],'singleSiteOnly':True,'originalDatabasePreserved':True})
        run(['systemctl','start','eatwhat-control.service'],timeout=60)
    except Exception:
        atomic(d/'receipt.json',{'id':h['id'],'serverId':h['serverId'],'state':'FAILED','dataPreserved':True,'configurationChanged':changed})
        # Before allocation changes, the unchanged configuration can restart safely. After changes,
        # retain the new binding; reverting a durable database identity is never an automatic rollback.
        if not changed:
            with contextlib.suppress(Exception): run(compose(root)+['up','-d','--no-deps','app'],timeout=60)
        with contextlib.suppress(Exception): run(['systemctl','start','eatwhat-control.service'],timeout=60)
    finally: lock.close()

def main():
    if sys.argv[1]=='run':
        h=json.loads(private(Path(sys.argv[2])).read_text());perform(h);return
    line=sys.stdin.buffer.readline(262145);require(len(line)<=262144 and line.endswith(b'\n'));h=json.loads(line)
    require(not sys.stdin.buffer.read(1))
    result={'inspect':inspect,'dispatch':dispatch,'status':status}[sys.argv[1]](h)
    print(json.dumps(result,separators=(',',':')))
if __name__=='__main__':
    try: main()
    except Exception: print('{"ready":false,"state":"REJECTED"}');sys.exit(1)
