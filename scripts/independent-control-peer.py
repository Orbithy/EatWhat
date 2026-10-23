#!/usr/bin/env python3
"""Privileged, fixed-inventory replica adapter. Invoked only by the local
operator service, never through HTTP. JSON packets stay on encrypted SSH/stdin.
No generic SQL, caller-chosen path, role grant, election, or password operation.
"""
import base64
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import zipfile
import urllib.request

CONFIG=Path('/opt/eatwhat-control/independent/operator.json')
TABLES=tuple('eatwhat_cluster.'+n for n in (
    'state','member','change','change_vote','audit','command','gc_operation','gc_vote',
    'control_process','control_handoff','deployment_fence','installation_allocation','recipient_replay','control_lease'))+(
    'eatwhat_controller.account','eatwhat_controller.credential_change')


def require(value,code='INDEPENDENT_PEER_BINDING_REQUIRED'):
    if not value:raise RuntimeError(code)


def q(identifier):
    require(re.fullmatch(r'[a-z_][a-z0-9_]*',identifier) is not None)
    return '"'+identifier+'"'


def table(name):
    require(name in TABLES);return '.'.join(q(part)for part in name.split('.'))


def literal(value):
    # Data is UTF-8 JSON. A random dollar tag never interprets quotes or backslashes.
    encoded=json.dumps(value,separators=(',',':'),ensure_ascii=False)
    tag='$control_'+hashlib.sha256(encoded.encode()).hexdigest()[:20]+'$'
    require(tag not in encoded);return tag+encoded+tag+'::jsonb'


class Peer:
    def __init__(self,config):
        self.c=config
        require(set(config)=={'controllerId','peerId','clusterId','stateId','name','postgresContainer','database','postgresPort','stateDirectory','policyFile'})
        require(config['controllerId']!=config['peerId'])
        for key in ['controllerId','peerId','clusterId','stateId']:
            import uuid
            require(str(uuid.UUID(config[key]))==config[key])
        require(config['stateDirectory']=='/opt/eatwhat-control/local-state')
        require(config['policyFile']=='/opt/eatwhat-control/independent/independent-control-policy.py')
        require(config['postgresContainer']in {'eatwhat-control-local-postgres','eatwhat-site-977105eb-postgres-1'})
        require(config['database']in {'eatwhat','eatwhat_control'} and config['postgresPort']in {5432,35432})
        spec=importlib.util.spec_from_file_location('independent_peer_policy',config['policyFile'])
        self.p=importlib.util.module_from_spec(spec);sys.modules[spec.name]=self.p;spec.loader.exec_module(self.p)
    def sql(self,sql):
        # Resolve only the existing container's superuser name, never a password.
        info=json.loads(subprocess.check_output(['docker','inspect',self.c['postgresContainer']]))[0]
        env=dict(x.split('=',1)for x in info['Config']['Env']if '='in x)
        args=['docker','exec','-i',self.c['postgresContainer'],'psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1',
            '-p',str(self.c['postgresPort']),'-U',env['POSTGRES_USER'],'-d',self.c['database']]
        result=subprocess.run(args,input=sql,text=True,capture_output=True,timeout=25)
        if result.returncode:
            match=re.search(r'ERROR:\s+(CONTROL_[A-Z_]+|CREDENTIAL_VERSION_CHANGED|IDEMPOTENCY_CONFLICT)',result.stderr)
            raise RuntimeError(match.group(1)if match else'INDEPENDENT_PEER_SQL_FAILED')
        lines=[line for line in result.stdout.splitlines()if line.strip()]
        return json.loads(lines[-1])if lines else None
    def status(self):
        d=self.sql("""SELECT json_build_object('cluster',cluster_id,'holder',holder_id,'generation',generation,
            'live',expires_at>clock_timestamp(),'at',clock_timestamp()) FROM eatwhat_cluster.control_lease WHERE singleton;""")
        require(d is not None and d['cluster']==self.c['clusterId']);return d
    def files(self):
        root=Path(self.c['stateDirectory']);require(root.is_dir()and not root.is_symlink())
        require((root/'controller-state-id').read_text()==self.c['stateId'])
        result={};total=0
        for path in sorted(root.rglob('*')):
            require(not path.is_symlink())
            if path.is_dir():continue
            require(path.is_file());relative=path.relative_to(root).as_posix()
            if relative.endswith(('.lock','.independent-copy')):continue
            data=path.read_bytes();total+=len(data);require(total<=32*1024*1024 and len(data)<=8*1024*1024)
            result[relative]={'data':base64.b64encode(data).decode(),'sha256':hashlib.sha256(data).hexdigest()}
        require('servers.key'in result and 'controller-state-id'in result)
        return result
    def export(self):
        expressions=','.join("'"+name+"',coalesce((SELECT jsonb_agg(to_jsonb(t))FROM "+table(name)+"t),'[]'::jsonb)"for name in TABLES)
        # A serialized READ COMMITTED statement captures all rows together and
        # obtains a strictly ordered local revision. A delayed SSH response can
        # never overwrite a newer same-generation credential/task snapshot.
        packet=self.sql("BEGIN;SET LOCAL lock_timeout='5s';SET LOCAL statement_timeout='20s';SELECT pg_advisory_xact_lock(1161909335,1100);"
            +"SELECT jsonb_build_object('revision',nextval('eatwhat_controller.snapshot_revision'),'tables',jsonb_build_object("+expressions+"),'lease',(SELECT to_jsonb(l)||jsonb_build_object('live',expires_at>clock_timestamp())FROM eatwhat_cluster.control_lease l WHERE singleton));COMMIT;")
        require(packet['lease']['cluster_id']==self.c['clusterId'])
        packet.update({'controllerId':self.c['controllerId'],'peerId':self.c['peerId'],'clusterId':self.c['clusterId'],'stateId':self.c['stateId'],'files':self.files()})
        return packet
    def heartbeat(self,peer):
        require(set(peer)=={'controllerId','clusterId','stateId','name','running'})
        require(peer['controllerId']==self.c['peerId']and peer['clusterId']==self.c['clusterId']and peer['stateId']==self.c['stateId'])
        if not peer['running']:return {'updated':False}
        values={'holder_id':peer['controllerId'],'cluster_id':peer['clusterId'],'state_id':peer['stateId'],'name':peer['name'],'lease_seconds':60}
        self.sql("INSERT INTO eatwhat_cluster.control_process(holder_id,cluster_id,state_id,name,lease_seconds,expires_at,deployment_protocol)"
            +"SELECT holder_id,cluster_id,state_id,name,lease_seconds,clock_timestamp()+interval '90 seconds',1 FROM jsonb_populate_record(NULL::eatwhat_cluster.control_process,"+literal(values)+")"
            +"WHERE true ON CONFLICT(holder_id)DO UPDATE SET expires_at=excluded.expires_at,lease_seconds=excluded.lease_seconds;SELECT json_build_object('updated',true);")
        return {'updated':True}
    def info(self):
        result=subprocess.run(['systemctl','is-active','eatwhat-control.service'],capture_output=True,text=True,timeout=5)
        running=False
        if result.returncode==0:
            try:
                pid=int(subprocess.check_output(['systemctl','show','eatwhat-control.service','-p','MainPID','--value'],text=True,timeout=5))
                args=Path('/proc/'+str(pid)+'/cmdline').read_bytes().decode().split('\0');jar=Path(args[args.index('-jar')+1])
                properties=dict(line.split('=',1)for line in Path('/opt/eatwhat-control/config/application.properties').read_text().splitlines()if '='in line and not line.startswith('#'))
                with zipfile.ZipFile(jar)as archive:marker=archive.read('META-INF/eatwhat-independent-control-v1')
                running=(marker==b'eatwhat-independent-control-v1\n'and properties.get('app.control.independent.enabled')=='true'
                    and properties.get('app.cluster.control-lease.automatic-takeover')=='false'
                    and properties.get('app.cluster.control-lease.controller-id')==self.c['controllerId'])
                if running:
                    with urllib.request.urlopen('http://127.0.0.1:18081/actuator/health/readiness',timeout=2)as response:
                        running=response.status==200 and json.load(response).get('status')=='UP'
            except Exception:running=False
        return {'controllerId':self.c['controllerId'],'clusterId':self.c['clusterId'],'stateId':self.c['stateId'],'name':self.c['name'],'running':running}
    def outbox(self):
        return self.sql("BEGIN;UPDATE eatwhat_controller.handoff_outbox SET state='DISPATCHING',reason='AUTHORITY_FORWARD_IN_PROGRESS'WHERE state='PENDING';SELECT coalesce(jsonb_agg(to_jsonb(q)),'[]'::jsonb)FROM eatwhat_controller.handoff_outbox q WHERE state='DISPATCHING';COMMIT;")
    def forward(self,request):
        require(self.info()['running'],'CONTROL_INDEPENDENT_RUNTIME_REQUIRED')
        required={'request_id','actor_id','credential_version','source_holder','target_holder','expected_generation','state','reason','requested_at'}
        require(set(request)==required and request['source_holder']==self.c['controllerId']and request['target_holder']==self.c['peerId']and request['state']=='DISPATCHING')
        body=literal(request)
        sql="""BEGIN;SET LOCAL lock_timeout='5s';SET LOCAL statement_timeout='15s';
            DO $forward$ DECLARE r eatwhat_controller.handoff_outbox;l eatwhat_cluster.control_lease;h eatwhat_cluster.control_handoff;
            BEGIN SELECT * INTO r FROM jsonb_populate_record(NULL::eatwhat_controller.handoff_outbox,"""+body+""");
            SELECT * INTO l FROM eatwhat_cluster.control_lease WHERE singleton FOR UPDATE;
            IF l.cluster_id<>'"""+self.c['clusterId']+"""'::uuid THEN RAISE EXCEPTION 'CONTROL_CLUSTER_BINDING_CHANGED';END IF;
            SELECT * INTO h FROM eatwhat_cluster.control_handoff WHERE request_id=r.request_id;
            IF FOUND THEN
              IF (h.source_holder,h.target_holder,h.expected_generation,h.actor)IS DISTINCT FROM(r.source_holder,r.target_holder,r.expected_generation,r.actor_id::text)
                THEN RAISE EXCEPTION 'IDEMPOTENCY_CONFLICT';END IF;
            ELSE
              IF l.holder_id<>r.source_holder OR l.generation<>r.expected_generation OR l.expires_at<=clock_timestamp()
                THEN RAISE EXCEPTION 'CONTROL_GENERATION_CHANGED';END IF;
              PERFORM 1 FROM eatwhat_controller.account WHERE id=r.actor_id AND NOT disabled AND credential_version=r.credential_version FOR SHARE;
              IF NOT FOUND THEN RAISE EXCEPTION 'CREDENTIAL_VERSION_CHANGED';END IF;
              IF NOT EXISTS(SELECT 1 FROM eatwhat_cluster.control_process WHERE holder_id=r.target_holder AND cluster_id=l.cluster_id AND state_id='"""+self.c['stateId']+"""'::uuid AND expires_at>clock_timestamp())
                THEN RAISE EXCEPTION 'CONTROL_TARGET_OFFLINE';END IF;
              IF EXISTS(SELECT 1 FROM eatwhat_cluster.control_handoff WHERE cluster_id=l.cluster_id AND state IN ('WAITING','TRANSFERRING'))
                THEN RAISE EXCEPTION 'CONTROL_HANDOFF_PENDING';END IF;
              INSERT INTO eatwhat_cluster.control_handoff(request_id,cluster_id,source_holder,target_holder,expected_generation,state,actor)
                VALUES(r.request_id,l.cluster_id,r.source_holder,r.target_holder,r.expected_generation,'WAITING',r.actor_id::text);
              INSERT INTO eatwhat_cluster.audit(request_id,actor,action,epoch)
                SELECT r.request_id,r.actor_id::text,'CONTROL_HANDOFF_REQUEST',epoch FROM eatwhat_cluster.state WHERE singleton;
            END IF;END $forward$;
            COMMIT;SELECT json_build_object('forwarded',true,'reason','AUTHORITY_REQUEST_ACCEPTED');"""
        try:return self.sql(sql)
        except RuntimeError as failure:
            reason=str(failure)
            if re.fullmatch(r'(CONTROL_[A-Z_]+|CREDENTIAL_VERSION_CHANGED|IDEMPOTENCY_CONFLICT)',reason):return {'forwarded':False,'reason':reason}
            raise
    def acknowledge(self,result):
        require(set(result)=={'requestId','forwarded','reason'}and isinstance(result['forwarded'],bool))
        require(re.fullmatch(r'[A-Z_]{1,64}',result['reason'])is not None)
        state='FORWARDED'if result['forwarded']else'REJECTED'
        body=literal({'request_id':result['requestId'],'state':state,'reason':result['reason']})
        self.sql("UPDATE eatwhat_controller.handoff_outbox q SET state=r.state,reason=r.reason FROM jsonb_populate_record(NULL::eatwhat_controller.handoff_outbox,"+body+")r WHERE q.request_id=r.request_id AND q.state='DISPATCHING';SELECT json_build_object('acknowledged',true);")
        return {'acknowledged':True}
    def install(self,packet):
        lock=CONFIG.with_name('mirror.lock')
        fd=os.open(lock,os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'r+b')as stream:
            fcntl.flock(stream,fcntl.LOCK_EX)
            return self._install(packet)
    def _install(self,packet):
        require(set(packet)=={'controllerId','peerId','clusterId','stateId','tables','lease','files','revision'})
        require(packet['controllerId']==self.c['peerId']and packet['peerId']==self.c['controllerId']and packet['clusterId']==self.c['clusterId']and packet['stateId']==self.c['stateId'])
        require(set(packet['tables'])==set(TABLES))
        source=packet['lease'];target=self.status()
        require(isinstance(packet['revision'],int)and 0<packet['revision']<2**63)
        cursor=self.sql("SELECT to_jsonb(c)FROM eatwhat_controller.snapshot_cursor c WHERE source_controller='"+packet['controllerId']+"'::uuid;")
        if cursor and(source['generation'],packet['revision'])<=(cursor['generation'],cursor['revision']):return {'state':'STALE_SNAPSHOT','changed':False}
        transfer=None
        pending=[h for h in packet['tables']['eatwhat_cluster.control_handoff']if h['state']=='TRANSFERRING'and h['target_holder']==self.c['controllerId']]
        if pending:
            require(len(pending)==1)
            h=pending[0];idle=not any(f['state']=='ACTIVE'for f in packet['tables']['eatwhat_cluster.deployment_fence'])
            transfer=self.p.Transfer(h['request_id'],h['source_holder'],h['target_holder'],h['expected_generation'],h['state'],idle)
        action=self.p.decide(packet['controllerId'],self.c['controllerId'],self.p.Lease(source['holder_id'],source['generation'],source['live']),self.p.Lease(target['holder'],target['generation'],target['live']),transfer)
        if action in (self.p.Action.STALE,self.p.Action.ALREADY_INSTALLED):return {'state':action.value,'changed':False}
        require(packet['tables']['eatwhat_cluster.control_lease']==[{k:v for k,v in source.items()if k!='live'}])
        # Validate every file and its path before installing the first byte.
        root=Path(self.c['stateDirectory']);validated=[];total=0
        for relative,item in packet['files'].items():
            require(set(item)=={'data','sha256'})
            path=Path(relative);require(not path.is_absolute()and all(p not in ('','..','.')for p in path.parts)and not relative.endswith('.lock'))
            data=base64.b64decode(item['data'],validate=True);total+=len(data)
            require(len(data)<=8*1024*1024 and total<=32*1024*1024 and hashlib.sha256(data).hexdigest()==item['sha256'])
            validated.append((path,data))
        require((Path('controller-state-id'),self.c['stateId'].encode())in validated)
        # The receiver is still fenced by its foreign holder. Final state files
        # precede the lease commit; the app cannot become leader partway through.
        owner=root.stat();require(not root.is_symlink()and stat.S_IMODE(owner.st_mode)==0o700)
        for relative,data in validated:
            target_path=root/relative
            for parent in relative.parents:
                require(not(root/parent).is_symlink())
            parent=root
            for part in relative.parts[:-1]:
                parent=parent/part
                if not parent.exists():parent.mkdir(mode=0o700);os.chown(parent,owner.st_uid,owner.st_gid)
                require(parent.is_dir()and not parent.is_symlink()and parent.stat().st_uid==owner.st_uid and stat.S_IMODE(parent.stat().st_mode)==0o700)
            temp=target_path.with_name('.'+target_path.name+'.independent-copy')
            require(not temp.is_symlink()and not target_path.is_symlink())
            if temp.exists():
                interrupted=temp.stat()
                require(stat.S_ISREG(interrupted.st_mode)and interrupted.st_uid==owner.st_uid and stat.S_IMODE(interrupted.st_mode)==0o600 and interrupted.st_nlink==1)
                temp.unlink()
            fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
            with os.fdopen(fd,'wb')as f:
                os.fchown(f.fileno(),owner.st_uid,owner.st_gid);f.write(data);f.flush();os.fsync(f.fileno())
            os.replace(temp,target_path)
        sql="BEGIN;SET LOCAL lock_timeout='5s';SET LOCAL statement_timeout='20s';SELECT pg_advisory_xact_lock(1161909335,1099);"
        # Assert the target did not acquire authority while files were copied.
        expected=literal({'holder_id':target['holder'],'generation':target['generation']})
        sql+="DO $guard$ BEGIN IF NOT EXISTS(SELECT 1 FROM eatwhat_cluster.control_lease l,jsonb_populate_record(NULL::eatwhat_cluster.control_lease,"+expected+")e WHERE l.singleton AND l.holder_id=e.holder_id AND l.generation=e.generation)THEN RAISE EXCEPTION 'TARGET_BASE_CHANGED';END IF;END $guard$;"
        # Root-owned replication replay only. Runtime roles cannot set this GUC;
        # immutable history is merged, never removed, and all tables are fixed.
        sql+="SET LOCAL session_replication_role=replica;"
        table_list=','.join("'"+name+"'"for name in TABLES)
        metadata=self.sql("""SELECT jsonb_object_agg(name,jsonb_build_object(
            'columns',(SELECT jsonb_agg(a.attname ORDER BY a.attnum)FROM pg_attribute a WHERE a.attrelid=name::regclass AND a.attnum>0 AND NOT a.attisdropped),
            'pk',(SELECT jsonb_agg(a.attname ORDER BY ord.n)FROM pg_index i CROSS JOIN LATERAL unnest(i.indkey)WITH ORDINALITY ord(attnum,n)JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=ord.attnum WHERE i.indrelid=name::regclass AND i.indisprimary),
            'sequences',coalesce((SELECT jsonb_object_agg(a.attname,pg_get_serial_sequence(name,a.attname))FROM pg_attribute a WHERE a.attrelid=name::regclass AND a.attnum>0 AND NOT a.attisdropped AND pg_get_serial_sequence(name,a.attname)IS NOT NULL),'{}'::jsonb)))
            FROM unnest(ARRAY["""+table_list+"])name;")
        for name in TABLES:
            rows=packet['tables'][name]
            if not rows:continue
            meta=metadata[name]
            columns=meta['columns'];keys=meta['pk'];require(columns and keys and all(set(row)==set(columns)for row in rows))
            column_sql=','.join(q(c)for c in columns);updates=','.join(q(c)+'=excluded.'+q(c)for c in columns if c not in keys)
            sql+='INSERT INTO '+table(name)+'('+column_sql+')SELECT '+column_sql+' FROM jsonb_populate_recordset(NULL::'+table(name)+','+literal(rows)+')WHERE true ON CONFLICT('+','.join(q(k)for k in keys)+')DO '+('UPDATE SET '+updates if updates else 'NOTHING')+';'
            for column,seq in meta['sequences'].items():
                require(re.fullmatch(r'[a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*',seq)is not None)
                sql+="SELECT setval('"+seq+"',greatest((SELECT last_value FROM "+'.'.join(q(p)for p in seq.split('.'))+"),coalesce((SELECT max("+q(column)+")FROM "+table(name)+"),1)),true);"
        if action==self.p.Action.INSTALL_RELEASE:
            # Transport may outlive the source's confirmation window. Only a
            # verified, quiescent, explicitly target-bound release reaches here.
            # Start its acknowledgement window after the final files and rows
            # arrive, using the receiver's own configured process duration.
            duration=self.sql("SELECT to_json(lease_seconds)FROM eatwhat_cluster.control_process WHERE holder_id='"+self.c['controllerId']+"'::uuid AND state_id='"+self.c['stateId']+"'::uuid;")
            require(type(duration)is int and 1<=duration<=3600)
            sql+="UPDATE eatwhat_cluster.control_lease SET expires_at=clock_timestamp()+("+str(duration)+"*interval '1 second'),updated_at=clock_timestamp()WHERE singleton;"
        sql+="INSERT INTO eatwhat_controller.snapshot_cursor(source_controller,generation,revision)VALUES('"+packet['controllerId']+"',"+str(source['generation'])+","+str(packet['revision'])+")ON CONFLICT(source_controller)DO UPDATE SET generation=excluded.generation,revision=excluded.revision,installed_at=clock_timestamp();"
        sql+="COMMIT;SELECT json_build_object('state','INSTALLED','generation',"+str(source['generation'])+");"
        result=self.sql(sql);result['action']=action.value;result['fileCount']=len(validated);return result


def load():
    require(os.geteuid()==0 and sys.platform=='linux')
    require(CONFIG.is_file()and not CONFIG.is_symlink()and stat.S_IMODE(CONFIG.stat().st_mode)==0o600)
    return Peer(json.loads(CONFIG.read_text()))


if __name__=='__main__':
    try:
        peer=load();request=json.load(sys.stdin);op=request['operation']
        if op=='status':result=peer.status()
        elif op=='info':result=peer.info()
        elif op=='export':result=peer.export()
        elif op=='heartbeat':result=peer.heartbeat(request['peer'])
        elif op=='outbox':result=peer.outbox()
        elif op=='forward':result=peer.forward(request['request'])
        elif op=='acknowledge':result=peer.acknowledge(request['result'])
        elif op=='install':result=peer.install(request['packet'])
        else:raise RuntimeError('INDEPENDENT_PEER_OPERATION_REFUSED')
        print(json.dumps(result,separators=(',',':')))
    except Exception as failure:
        # Never include SQL, file content, requests, exceptions or credentials.
        print(json.dumps({'code':'INDEPENDENT_PEER_OPERATION_FAILED','errorType':type(failure).__name__}));sys.exit(1)
