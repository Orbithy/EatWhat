#!/usr/bin/env python3
"""Real, isolated Compose/HTTP/JAR/pg_dump exercise; does not use SSH or production services."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('agent', ROOT / 'deploy/remote-release.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
java = Path(os.environ['JAVA_HOME'])
docker = shutil.which('docker')
# Use an already-installed JRE image explicitly; pin its local digest for this run. No pull/build.
image_name = os.environ['EATWHAT_DEPLOYMENT_TEST_JRE_IMAGE']
image = subprocess.check_output([docker, 'image', 'inspect', '--format', '{{.Id}}', image_name], text=True).strip()
project = 'eatwhat-test-deployment-agent-' + uuid.uuid4().hex[:12]
instance = str(uuid.uuid4())
steps = []
with tempfile.TemporaryDirectory(prefix='eatwhat-deployment-agent-') as temporary:
    root = Path(temporary)
    source = root / 'Server.java'
    source.write_text('''
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
public class Server {
 public static void main(String[] args)throws Exception {
  var server=HttpServer.create(new InetSocketAddress("0.0.0.0",8080),0);
  server.createContext("/",e->{
   String value=e.getRequestURI().getPath().equals("/actuator/health/readiness")?"{\\"status\\":\\"UP\\"}":"{\\"siteId\\":\\"campus-fixture\\",\\"instanceId\\":\\"INSTANCE\\",\\"observationStatus\\":\\"OBSERVED\\"}";
   byte[] body=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
  });server.start();
 }
}
'''.replace('INSTANCE', instance))
    classes = root / 'classes'; classes.mkdir()
    subprocess.run([str(java/'bin/javac'), '-d', str(classes), str(source)], check=True, capture_output=True)
    jars = []
    for version in ['old', 'new']:
        resource = root / 'version.txt'; resource.write_text(version)
        jar = root / (version + '.jar')
        subprocess.run([str(java/'bin/jar'), '--create', '--file', str(jar), '--main-class', 'Server', '-C', str(classes), '.', '-C', str(root), 'version.txt'], check=True, capture_output=True)
        jars.append(jar)
    current = root / 'current.jar'; shutil.copyfile(jars[0], current); current.chmod(0o644)
    with socket.socket() as free:
        free.bind(('127.0.0.1', 0)); port = free.getsockname()[1]
    env = root / 'fixture.env'; env.write_text('APP_JAR=' + str(current) + '\n'); env.chmod(0o600)
    token = root / 'token'; token.write_text('synthetic-' + 'x'*50); token.chmod(0o600)
    compose = root / 'compose.json'
    compose.write_text(json.dumps({'services': {
        'app': {'image': image, 'entrypoint': ['java', '-Xmx64m', '-jar', '/app/app.jar'], 'user':'10001:10001',
                'volumes':[{'type':'bind','source':'${APP_JAR}','target':'/app/app.jar','read_only':True}], 'ports':[f'127.0.0.1:{port}:8080'], 'networks':['private']},
        'postgres': {'image':'postgres:16', 'environment':{'POSTGRES_USER':'fixture','POSTGRES_PASSWORD':'synthetic-fixture-password','POSTGRES_DB':'agent_fixture','PG_DB':'agent_fixture'},
                     'healthcheck':{'test':['CMD-SHELL','pg_isready -U fixture -d agent_fixture'],'interval':'1s','timeout':'2s','retries':90},'networks':['private']}
    }, 'networks': {'private': {}}}))
    config = root / 'release.json'; config.write_text(json.dumps(dict(site_id='campus-fixture', instance_id=instance,
        project=project, compose_file=str(compose), env_file=str(env), active_jar=str(current), state_directory=str(root/'state'),
        node_token_file=str(token), origin=f'http://127.0.0.1:{port}', docker=docker, control_host=False, restore_evidence_id='isolated-fixture',
        command_timeout_seconds=60, health_timeout_seconds=15, minimum_free_bytes=0)))
    config.chmod(0o600)
    command = [docker,'compose','--project-name',project,'--env-file',str(env),'-f',str(compose)]
    try:
        subprocess.run(command+['up','-d','--wait','--wait-timeout','100'], check=True, capture_output=True, timeout=120)
        agent=module.Agent(config);old=module.sha_file(current)
        agent.wait_ready(old, 'campus-fixture', instance);steps.append('real_old_java_app_ready')
        data=jars[1].read_bytes();sha=hashlib.sha256(data).hexdigest();job=str(uuid.uuid4())
        assert agent.operate('prepare',job,sha,'campus-fixture',instance,old,io.BytesIO())['state']=='PREPARED'
        result=agent.operate('apply',job,sha,'campus-fixture',instance,old,io.BytesIO(data));assert result['state']=='SUCCEEDED',result
        assert agent.runtime_sha()==sha;steps.append('actual_compose_app_recreation_and_runtime_jar_sha')
        dump=root/'state'/job/'database.dump';assert dump.stat().st_size>0;steps.append('real_postgres_dump_and_restore_list')
        assert agent.operate('apply',job,sha,'campus-fixture',instance,old,io.BytesIO(data))['state']=='SUCCEEDED';steps.append('duplicate_request_reads_remote_journal')
        bad=b'invalid jar - forces Java startup failure';bad_sha=hashlib.sha256(bad).hexdigest();failed=str(uuid.uuid4())
        result=agent.operate('apply',failed,bad_sha,'campus-fixture',instance,sha,io.BytesIO(bad));assert result['state']=='ROLLED_BACK',result
        assert agent.runtime_sha()==sha;steps.append('actual_failed_java_start_restores_previous_jar_and_readiness')
        output=ROOT/'output'/'deployment-agent-compose';output.mkdir(parents=True,exist_ok=True)
        (output/'report.json').write_text(json.dumps({'status':'PASS','scope':'LOCAL_REAL_COMPOSE_JAVA_HTTP_POSTGRES','productionDeployment':False,'sshTransport':'NOT_USED','runtimeImage':image,'steps':steps},indent=2))
        print(json.dumps({'status':'PASS','steps':len(steps),'report':str(output/'report.json')}))
    except Exception:
        try:
            print('Isolated fixture app log:', subprocess.check_output(command+['logs','--no-color','--tail','20','app'],stderr=subprocess.DEVNULL,text=True)[-1800:])
            print('Isolated fixture readiness:', agent.request('/actuator/health/readiness'))
            print('Isolated fixture identity:', agent.request('/internal/control/v1/snapshot',True))
            print('Isolated fixture runtime SHA:', agent.runtime_sha())
        except Exception as diagnostic:
            print('Isolated fixture diagnostic class:',type(diagnostic).__name__)
        raise
    finally:
        # Only this unique test project, created above. No external volumes or existing services.
        subprocess.run(command+['down','--volumes','--remove-orphans'], check=True, capture_output=True, timeout=60)
