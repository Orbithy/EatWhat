#!/usr/bin/env python3
"""Fixed two-site operator migration. Credentials never leave server-private files.
Preparation does not replace the running unit/config. Activate only after the
coordinated stop, final file copy, binding and source-only authority snapshot.
"""
import os,sys,json,pathlib,subprocess,secrets,pwd,hashlib,uuid,shutil,re
ROOT=pathlib.Path('/opt/eatwhat-control');OP=ROOT/'independent';BACK=OP/'before';LOCAL=ROOT/'local-state'
SHA='e4efda99b8bebfe905d530bae8630d546b1118f9a300eb5ae44867392b2e47f1'
JAR=ROOT/'acceptance'/(SHA+'.jar');ROLE='eatwhat_control_independent_v1'
CAMPUS='973fe4e6-c894-4591-9a66-1044827268ee';SINGAPORE='72b207a5-5580-40cf-92db-51d190c0932c'
CLUSTER='74d888b2-76a7-4f26-b37f-a1645dbd3598'
TABLES=['state','member','change','change_vote','audit','command','gc_operation','gc_vote','control_process','control_handoff','deployment_fence','installation_allocation','recipient_replay','control_lease']
def require(v):
 if not v:raise RuntimeError('MIGRATION_PRECONDITION_FAILED')
def run(args,data=None,timeout=40):
 r=subprocess.run(args,input=data,text=isinstance(data,str),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
 if r.returncode:raise RuntimeError('OPERATOR_COMMAND_FAILED')
 return r.stdout
def private(path,data,uid=0,gid=0,replace=False):
 require(not path.is_symlink()and(replace or not path.exists()));temp=path.with_name('.'+path.name+'.new');require(not temp.exists())
 fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
 with os.fdopen(fd,'wb')as f:os.fchown(f.fileno(),uid,gid);f.write(data.encode()if isinstance(data,str)else data);f.flush();os.fsync(f.fileno())
 os.replace(temp,path)
def raw(path):return dict(l.split('=',1)for l in path.read_text().splitlines()if '='in l and not l.startswith('#'))
def decode(v):return re.sub(r'\\(.)',lambda m:{'n':'\n','r':'\r','t':'\t'}.get(m[1],m[1]),v)
def props(d):return ''.join(k+'='+v.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n'for k,v in sorted(d.items()))
def setup():
 global SG,CONTAINER,PORT,DATABASE,UID,GID,CONFIG,ADMIN,URL
 original=BACK/'application.properties';CONFIG={k:decode(v)for k,v in raw(original if original.exists()else ROOT/'config/application.properties').items()}
 SG=CONFIG['app.cluster.control-lease.controller-name']=='singapore-control';CONTAINER='eatwhat-control-local-postgres'if SG else'eatwhat-site-977105eb-postgres-1';PORT=35432 if SG else 5432;DATABASE='eatwhat_control'if SG else'eatwhat';URL='jdbc:postgresql://127.0.0.1:'+('35432/eatwhat_control'if SG else'25432/eatwhat')
 u=pwd.getpwnam('eatwhat-control');UID,GID=u.pw_uid,u.pw_gid
 c=json.loads(run(['docker','inspect',CONTAINER]))[0];ADMIN=dict(v.split('=',1)for v in c['Config']['Env']if '='in v)
def sql(query):
 result=run(['docker','exec','-i',CONTAINER,'psql','-XqAt','-v','ON_ERROR_STOP=1','-p',str(PORT),'-U',ADMIN['POSTGRES_USER'],'-d',DATABASE],query)
 lines=[l for l in result.splitlines()if l.strip()];return json.loads(lines[-1])if lines else None
def prepare():
 require(hashlib.sha256(JAR.read_bytes()).hexdigest()==SHA)
 if (OP/'prepared.json').exists():print('{"state":"ALREADY_PREPARED"}');return
 OP.mkdir(mode=0o700,exist_ok=True);OP.chmod(0o700);BACK.mkdir(mode=0o700,exist_ok=True)
 for name,path in [('application.properties',ROOT/'config/application.properties'),('unit',pathlib.Path('/etc/systemd/system/eatwhat-control.service'))]:
  if not(BACK/name).exists():private(BACK/name,path.read_bytes())
 drop=pathlib.Path('/etc/systemd/system/eatwhat-control.service.d')
 if drop.exists()and not(BACK/'unit.d').exists():shutil.copytree(drop,BACK/'unit.d')
 if not(BACK/'database.dump').exists():
  private(BACK/'database.dump',run(['docker','exec',CONTAINER,'pg_dump','-Fc','--no-owner','--no-acl','-p',str(PORT),'-U',ADMIN['POSTGRES_USER'],'-d',DATABASE],timeout=50))
 password=secrets.token_urlsafe(36);require(not sql("SELECT to_json(EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+ROLE+"'));"))
 sql("CREATE ROLE "+ROLE+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION CONNECTION LIMIT 16 PASSWORD '"+password+"';")
 schemas=sql("SELECT json_agg(nspname)FROM pg_namespace WHERE nspname='public'OR nspname LIKE 'eatwhat_%';")
 statement='GRANT CONNECT ON DATABASE '+DATABASE+' TO '+ROLE+';'
 for schema in schemas:
  require(re.fullmatch('[a-z_][a-z0-9_]*',schema)is not None);statement+='GRANT USAGE ON SCHEMA '+schema+' TO '+ROLE+';GRANT SELECT ON ALL TABLES IN SCHEMA '+schema+' TO '+ROLE+';'
 for name in TABLES:statement+='GRANT SELECT,INSERT,UPDATE ON eatwhat_cluster.'+name+' TO '+ROLE+';'
 statement+='GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA eatwhat_cluster TO '+ROLE+';'
 sql(statement)
 controller=SINGAPORE if SG else CAMPUS;installation=str(uuid.uuid4())
 plan={'bootstrap.jdbc.url':URL,'bootstrap.jdbc.user':ADMIN['POSTGRES_USER'],'bootstrap.jdbc.password':(pathlib.Path('/opt/eatwhat-control-local/config/pg-admin.password').read_text().strip()if SG else ADMIN['POSTGRES_PASSWORD']),
 'bootstrap.installation-id':installation,'bootstrap.controller-id':controller,'bootstrap.authority-site-id':'server-977105eb0b0f','bootstrap.authority-instance-id':'645f6435-84c0-4a7e-9463-0a97fac9e4b8','bootstrap.cluster-id':CLUSTER,'bootstrap.runtime-role':ROLE,
 'bootstrap.source-site-id':'server-16343d722687'if SG else'server-977105eb0b0f','bootstrap.source-instance-id':'362c585b-c12f-4b66-afb5-e507b102811b'if SG else'645f6435-84c0-4a7e-9463-0a97fac9e4b8'}
 if not SG:
  account=sql("SELECT json_build_object('id',id,'username',nick_name,'hash',password_hash)FROM public.users WHERE id=1000000001 AND nick_name='rpc' AND role='admin';");require(account is not None)
  plan.update({'bootstrap.management-account-id':str(account['id']),'bootstrap.management-username':account['username'],'bootstrap.management-password-hash':account['hash']})
 private(OP/'provision.properties',props(plan));private(OP/'runtime.password',password+'\n')
 target=dict(CONFIG)
 if SG:
  business={k:decode(v)for k,v in raw(pathlib.Path('/opt/eatwhat-sites/16343d72-2687-4e01-a8b1-9d9efe881474/config/application.properties')).items()}
  # Container identity fields can be blank in the file and supplied by its
  # environment. The management JVM has a separate environment: bind its
  # actual physical allocation explicitly instead of copying those blanks.
  allocation=sql("SELECT row_to_json(a)FROM eatwhat_identity.allocation a WHERE singleton;")
  require(allocation['site_id']==plan['bootstrap.source-site-id']and allocation['instance_id']==plan['bootstrap.source-instance-id']and allocation['cluster_id']==CLUSTER and allocation['slot']==1)
  target.update({'app.site.id':allocation['site_id'],'app.site.instance-id':allocation['instance_id'],'app.site.cluster-id':CLUSTER,'app.site.mode':'EXTERNAL','app.site.allocation-slot':'1'})
  target.update({'app.cluster.authority-site-id':plan['bootstrap.authority-site-id'],'app.cluster.authority-instance-id':plan['bootstrap.authority-instance-id']})
  app=json.loads(run(['docker','inspect','eatwhat-site-16343d72-app-1']))[0]
  environment=dict(v.split('=',1)for v in app['Config']['Env']if '='in v)
  normalize=lambda value:re.sub('[^A-Z0-9]','',value.upper())
  for key,value in business.items():
   if not key.startswith('app.storage.s3.'):continue
   matches=[v for k,v in environment.items()if normalize(k)==normalize(key)];require(len(matches)<=1)
   if matches:value=matches[0]
   require('${'not in value);target[key]=value
  target.update({'spring.data.redis.port':'26379','spring.data.redis.password':pathlib.Path('/opt/eatwhat-control-local/config/redis.password').read_text().strip(),'spring.data.redis.database':'0'})
 target.update({'spring.datasource.url':URL,'spring.datasource.username':ROLE,'spring.datasource.password':password,'spring.flyway.url':URL,'spring.flyway.user':ROLE,'spring.flyway.password':password,
 'app.control.database-mode':'independent-local','app.control.shared-state-required':'false','app.control.state-directory':str(LOCAL),'app.control.independent.enabled':'true','app.control.independent.controller-id':controller,'app.control.independent.installation-id':installation,
 'app.control.independent.authority-site-id':plan['bootstrap.authority-site-id'],'app.control.independent.authority-instance-id':plan['bootstrap.authority-instance-id'],'app.cluster.control-lease.controller-id':controller,'app.cluster.control-lease.automatic-takeover':'false','app.control.servers.ssh-routes-file':str(ROOT/'config/ssh-routes.json'),
 'app.cluster.control-lease.duration-seconds':'60',
 'app.control.deployment.controller-artifact':str(JAR)})
 # This process only drives the cluster's real business nodes. It never applies
 # business outboxes or scheduled catalog rewrites into its local SQL projection.
 for domain in ['activity','relation','profile','privacy','notification','catalog','files','browse','preferences']:target['app.sync.'+domain+'.enabled']='false'
 target['app.sync.capture-enabled']='false';target['app.sync.transport-enabled']='false'
 target={k:v.replace(str(ROOT/'state'),str(LOCAL))for k,v in target.items()}
 private(OP/'application.next.properties',props(target),UID,GID)
 state=(ROOT/'state/controller-state-id').read_text();require(str(uuid.UUID(state))==state)
 operator={'controllerId':controller,'peerId':CAMPUS if SG else SINGAPORE,'clusterId':CLUSTER,'stateId':state,'name':target['app.cluster.control-lease.controller-name'],'postgresContainer':CONTAINER,'database':DATABASE,'postgresPort':PORT,'stateDirectory':str(LOCAL),'policyFile':str(OP/'independent-control-policy.py')}
 private(OP/'operator.json',json.dumps(operator,indent=2)+'\n')
 unit='''[Unit]
Description=EatWhat independent local management controller
Requires=docker.service
After=docker.service network-online.target
[Service]
Type=simple
User=eatwhat-control
Group=eatwhat-control
WorkingDirectory=/opt/eatwhat-control
ExecStart=/usr/bin/java -Xms128m -Xmx768m -jar '''+str(JAR)+''' --spring.config.location=file:/opt/eatwhat-control/config/application.properties --spring.config.additional-location= --spring.config.import=
Restart=on-failure
RestartSec=10
TimeoutStopSec=45
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/opt/eatwhat-control/local-state
BindReadOnlyPaths=/opt/eatwhat-control/config/storage-hosts:/etc/hosts
MemoryMax=1536M
[Install]
WantedBy=multi-user.target
'''
 private(OP/'unit.next',unit);private(OP/'prepared.json',json.dumps({'state':'PREPARED_NOT_ACTIVE','singapore':SG,'artifact':SHA,'controllerId':controller,'installationId':installation})+'\n')
 print(json.dumps({'state':'PREPARED_NOT_ACTIVE','singapore':SG,'businessDataMoved':False}))
def provision(schema_only=False):
 args=['java','-Dloader.main=you.v50to.eatwhat.control.independent.IndependentControlProvisioning','-cp',str(JAR),'org.springframework.boot.loader.launch.PropertiesLauncher','--config',str(OP/'provision.properties')]
 if schema_only:args+=['--schema-only']
 r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=45);private(OP/('schema.log'if schema_only else'bind.log'),r.stdout,replace=True)
 require(r.returncode==0);print(json.dumps({'state':'SCHEMA_READY'if schema_only else'INSTALLATION_BOUND','singapore':SG}))
def freeze():
 if not SG:
  require(sql("SELECT to_json(count(*)=0)FROM eatwhat_cluster.deployment_fence WHERE state='ACTIVE';"));require(sql("SELECT to_json(count(*)=0)FROM eatwhat_cluster.control_handoff WHERE state IN('WAITING','TRANSFERRING');"));require(sql("SELECT to_json(holder_id='"+CAMPUS+"'::uuid AND expires_at>clock_timestamp())FROM eatwhat_cluster.control_lease WHERE singleton;"))
 run(['systemctl','stop','eatwhat-control.service'],timeout=52)
 # Copy only after the controller has released all file writers.
 if not(BACK/'state').exists():shutil.copytree(ROOT/'state',BACK/'state')
 require(not LOCAL.exists());shutil.copytree(ROOT/'state',LOCAL,ignore=shutil.ignore_patterns('*.lock'))
 for p in [LOCAL,*LOCAL.rglob('*')]:
  require(not p.is_symlink());os.chown(p,UID,GID);p.chmod(0o700 if p.is_dir()else 0o600)
 print(json.dumps({'state':'OLD_CONTROLLER_STOPPED_LOCAL_FILES_COPIED','singapore':SG}))
def activate():
 require(sql("SELECT to_json(count(*)=1)FROM eatwhat_controller.installation;"))
 # Pin explicit loopback routing on campus too, including the exact host key.
 if not SG:
  registry=json.loads((LOCAL/'servers.json').read_text());servers=registry['servers'];routes={'version':1,'routes':{}}
  for ident,s in servers.items():
   if s['host']in {'127.0.0.1','localhost','::1'}:routes['routes'][ident]={'host':s['host'],'port':s['port'],'logicalHost':s['host'],'logicalPort':s['port'],'hostKey':s['hostKey']}
  private(ROOT/'config/ssh-routes.json',json.dumps(routes,indent=2)+'\n',UID,GID,replace=(ROOT/'config/ssh-routes.json').exists())
 for p in (ROOT/'config').glob('*.json'):
  data=p.read_text()
  if str(ROOT/'state')in data:
   backup=BACK/('config-'+p.name)
   if not backup.exists():private(backup,p.read_bytes())
   private(p,data.replace(str(ROOT/'state'),str(LOCAL)),UID,GID,replace=True)
 private(ROOT/'config/application.properties',(OP/'application.next.properties').read_bytes(),UID,GID,replace=True)
 private(pathlib.Path('/etc/systemd/system/eatwhat-control.service'),(OP/'unit.next').read_bytes(),replace=True)
 old_drop=pathlib.Path('/etc/systemd/system/eatwhat-control.service.d/30-nfs-procfs-check.conf')
 if old_drop.exists():old_drop.unlink()
 run(['systemctl','daemon-reload']);run(['systemctl','start','eatwhat-control.service'],timeout=40)
 print(json.dumps({'state':'INDEPENDENT_CONTROLLER_STARTED','singapore':SG}))
def main():
 require(os.geteuid()==0 and sys.platform=='linux'and len(sys.argv)==2);setup();action=sys.argv[1]
 if action=='--prepare':prepare()
 elif action=='--schema':provision(True)
 elif action=='--bind':provision()
 elif action=='--freeze':freeze()
 elif action=='--activate':activate()
 else:raise RuntimeError('UNKNOWN_ACTION')
if __name__=='__main__':
 try:main()
 except Exception as e:print(json.dumps({'state':'MIGRATION_STOPPED','errorType':type(e).__name__}));sys.exit(1)
