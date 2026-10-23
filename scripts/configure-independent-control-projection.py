#!/usr/bin/env python3
"""Local-Singapore business projection for the NEW management database.

Only --inspect is read-only. --apply creates a local replication role/publication,
enables WAL logical decoding with a backed-up PostgreSQL setting, and performs
one controlled restart of Singapore's business PostgreSQL. It never changes the
running controller or copies campus consumer data. Credentials stay server-local.
"""
import json,os,pathlib,re,secrets,subprocess,sys,time

ROOT=pathlib.Path('/opt/eatwhat-control-local')
SOURCE='eatwhat-site-16343d72-postgres-1'
TARGET='eatwhat-control-local-postgres'
ROLE='eatwhat_control_local_projection'
PUBLICATION='eatwhat_control_local_business_v1'
SUBSCRIPTION='eatwhat_local_business_projection'
EXCLUDED={'state','member','change','change_vote','audit','command','gc_operation','gc_vote',
    'control_process','control_handoff','deployment_fence','installation_allocation','recipient_replay','control_lease'}


def require(value,code='LOCAL_PROJECTION_PRECONDITION_FAILED'):
    if not value:raise RuntimeError(code)


def command(args,data=None,timeout=40):
    result=subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    require(result.returncode==0)
    return result.stdout


def inspect(name):return json.loads(command(['docker','inspect',name]))[0]


def query(container,sql,database='eatwhat',port=5432):
    info=inspect(container);env=dict(x.split('=',1)for x in info['Config']['Env']if '='in x)
    output=command(['docker','exec','-i',container,'psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-p',str(port),'-U',env['POSTGRES_USER'],'-d',database],sql.encode())
    rows=[line for line in output.decode().splitlines()if line.strip()]
    return json.loads(rows[-1])if rows else None


def q(value):
    require(re.fullmatch(r'[a-z_][a-z0-9_]*',value)is not None);return '"'+value+'"'


def private(path,data,uid=0):
    require(not path.exists()and not path.is_symlink())
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'wb')as f:os.fchown(f.fileno(),uid,0);f.write(data);f.flush();os.fsync(f.fileno())


def save(data):
    temp=ROOT/'projection-progress.tmp';temp.write_text(json.dumps(data,indent=2)+'\n');temp.chmod(0o600);os.replace(temp,ROOT/'projection-progress.json')
    print(json.dumps({k:v for k,v in data.items()if k in {'state','tableCount','restartCompleted','readyRelations','totalRelations'}}),flush=True)


def main():
    require(os.geteuid()==0 and sys.platform=='linux'and sys.argv[1:]in [['--inspect'],['--apply']])
    require(json.loads((ROOT/'prepared.json').read_text())['state']=='PREPARED_NOT_CONNECTED')
    source=inspect(SOURCE);require(source['State']['Running'])
    tables=query(SOURCE,"""SELECT coalesce(json_agg(json_build_object('schema',n.nspname,'name',c.relname,'identity',c.relreplident,'hasPrimaryKey',EXISTS(SELECT 1 FROM pg_index i WHERE i.indrelid=c.oid AND i.indisprimary))ORDER BY n.nspname,c.relname),'[]'::json)
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='r'AND(n.nspname='public'OR n.nspname LIKE 'eatwhat_%')AND n.nspname<>'eatwhat_controller'
        AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.objid=c.oid AND d.classid='pg_class'::regclass AND d.refclassid='pg_extension'::regclass AND d.deptype='e');""")
    tables=[t for t in tables if not(t['schema']=='eatwhat_cluster'and t['name']in EXCLUDED)]
    wal=query(SOURCE,"SELECT to_json(current_setting('wal_level'));")
    if sys.argv[1]=='--inspect':
        print(json.dumps({'state':'PREFLIGHT','tableCount':len(tables),'walLevel':wal,'businessPostgresRestartRequired':wal!='logical','newControllerDatabaseOnly':True,'campusDataCopied':False}));return
    require(len(tables)>20)
    progress_path=ROOT/'projection-progress.json'
    progress=json.loads(progress_path.read_text())if progress_path.exists()else{'state':'INITIAL','tableCount':len(tables)}
    require(progress['tableCount']==len(tables))
    if progress['state']=='INITIAL':
        env=dict(x.split('=',1)for x in source['Config']['Env']if '='in x)
        backup=command(['docker','exec',SOURCE,'pg_dump','-Fc','--no-owner','--no-acl','-U',env['POSTGRES_USER'],'-d','eatwhat'],timeout=60)
        private(ROOT/'config/business-before-local-projection.dump',backup)
        auto=command(['docker','exec',SOURCE,'sh','-c','cat "$PGDATA/postgresql.auto.conf"'])
        private(ROOT/'config/postgresql.auto.conf.before-projection',auto)
        progress['state']='BACKED_UP';save(progress)
    if progress['state']=='BACKED_UP':
        if wal!='logical':
            query(SOURCE,"ALTER SYSTEM SET wal_level='logical';")
            query(SOURCE,"ALTER SYSTEM SET max_slot_wal_keep_size='1GB';")
            command(['docker','restart','--time','30',SOURCE],timeout=65)
            deadline=time.monotonic()+45
            while True:
                try:
                    require(query(SOURCE,"SELECT to_json(current_setting('wal_level'));")=='logical');break
                except Exception:
                    if time.monotonic()>deadline:raise
                    time.sleep(1)
        progress.update({'state':'WAL_READY','restartCompleted':True});save(progress)
    if progress['state']=='WAL_READY':
        password_path=ROOT/'config/replication.password'
        if password_path.exists():password=password_path.read_text().strip()
        else:password=secrets.token_urlsafe(36);private(password_path,(password+'\n').encode())
        exists=query(SOURCE,"SELECT to_json(EXISTS(SELECT 1 FROM pg_roles WHERE rolname='"+ROLE+"'));")
        require(not exists,'LOCAL_REPLICATION_ROLE_ALREADY_EXISTS')
        schemas=sorted({t['schema']for t in tables})
        sql='BEGIN;SET LOCAL lock_timeout=\'5s\';CREATE ROLE '+q(ROLE)+" LOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 6 PASSWORD '"+password+"';"
        sql+='GRANT CONNECT ON DATABASE eatwhat TO '+q(ROLE)+';'
        for schema in schemas:sql+='GRANT USAGE ON SCHEMA '+q(schema)+' TO '+q(ROLE)+';'
        for t in tables:
            name=q(t['schema'])+'.'+q(t['name']);sql+='GRANT SELECT ON '+name+' TO '+q(ROLE)+';'
            if not t['hasPrimaryKey']and t['identity']!='f':sql+='ALTER TABLE '+name+' REPLICA IDENTITY FULL;'
        sql+='CREATE PUBLICATION '+q(PUBLICATION)+' FOR TABLE '+','.join(q(t['schema'])+'.'+q(t['name'])for t in tables)+';COMMIT;'
        query(SOURCE,sql);private(ROOT/'config/publication-tables.json',(json.dumps(tables,indent=2)+'\n').encode())
        progress['state']='PUBLICATION_READY';save(progress)
    if progress['state']=='PUBLICATION_READY':
        source=inspect(SOURCE);networks=list(source['NetworkSettings']['Networks'].values());require(len(networks)==1)
        address=networks[0]['IPAddress'];require(re.fullmatch(r'[0-9.]+',address)is not None)
        postgres_uid=int(command(['docker','exec',TARGET,'id','-u','postgres']).decode().strip())
        password=(ROOT/'config/replication.password').read_text().strip()
        passfile=ROOT/'config/replication.pgpass'
        if not passfile.exists():private(passfile,(address+':5432:eatwhat:'+ROLE+':'+password+'\n').encode(),postgres_uid)
        compose=json.loads((ROOT/'compose.json').read_text());mount=str(passfile)+':/run/secrets/replication.pgpass:ro'
        if mount not in compose['services']['postgres']['volumes']:
            compose['services']['postgres']['volumes'].append(mount)
            private(ROOT/'compose.before-projection.json',(ROOT/'compose.json').read_bytes())
            (ROOT/'compose.json').write_text(json.dumps(compose,indent=2)+'\n');(ROOT/'compose.json').chmod(0o600)
            command(['docker','compose','-f',str(ROOT/'compose.json'),'up','-d','postgres'],timeout=70)
        deadline=time.monotonic()+35
        while True:
            try:query(TARGET,'SELECT to_json(true);',database='eatwhat_control',port=35432);break
            except Exception:
                if time.monotonic()>deadline:raise
                time.sleep(1)
        connection='host='+address+' port=5432 dbname=eatwhat user='+ROLE+' passfile=/run/secrets/replication.pgpass connect_timeout=5 application_name=eatwhat-control-local-projection'
        query(TARGET,'CREATE SUBSCRIPTION '+q(SUBSCRIPTION)+" CONNECTION '"+connection+"' PUBLICATION "+q(PUBLICATION)+" WITH(copy_data=true,create_slot=true,enabled=true,slot_name='eatwhat_control_local_projection_v1');",database='eatwhat_control',port=35432)
        progress['state']='COPYING_LOCAL_BUSINESS_PROJECTION';save(progress)
    if progress['state']=='COPYING_LOCAL_BUSINESS_PROJECTION':
        deadline=time.monotonic()+180;last=None
        while True:
            counts=query(TARGET,"SELECT json_build_object('totalRelations',count(*),'readyRelations',count(*)FILTER(WHERE srsubstate='r'))FROM pg_subscription_rel;",database='eatwhat_control',port=35432)
            if counts!=last:progress.update(counts);save(progress);last=counts
            if counts['totalRelations']==len(tables)and counts['readyRelations']==len(tables):break
            require(time.monotonic()<deadline,'LOCAL_PROJECTION_COPY_STILL_PENDING');time.sleep(2)
        progress['state']='LOCAL_BUSINESS_PROJECTION_READY';save(progress)


if __name__=='__main__':
    try:main()
    except Exception as failure:
        print(json.dumps({'state':'LOCAL_PROJECTION_SETUP_INCOMPLETE','errorType':type(failure).__name__,'controllerChanged':False}),flush=True);sys.exit(1)
