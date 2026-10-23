#!/usr/bin/env python3
"""Read-only project collision checks against isolated, test-owned empty Docker resources."""
import importlib.util, json, os, shutil, subprocess, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('install_agent',ROOT/'src/main/resources/control/first-install.py');agent=importlib.util.module_from_spec(spec);spec.loader.exec_module(agent)
docker=shutil.which('docker');assert docker
endpoint=subprocess.check_output([docker,'context','inspect','--format','{{.Endpoints.docker.Host}}'],text=True).strip();assert endpoint.startswith('unix://'),'Only an existing local Unix Docker context is accepted'
agent.ENV['DOCKER_HOST']=endpoint
server=str(uuid.uuid4());project='eatwhat-site-'+server[:8];label='eatwhat.test=first-install-project-guard';created=[];checks=[]
def command(args):return subprocess.check_output([docker]+args,env={**os.environ,'DOCKER_HOST':endpoint},stderr=subprocess.DEVNULL,text=True).strip()
def volume(name,extra=()):
 assert not command(['volume','ls','-q','--filter','name='+name])
 command(['volume','create','--label',label,*extra,name]);created.append(name)
def remove(name):
 details=json.loads(command(['volume','inspect',name]))[0];assert details['Labels'].get('eatwhat.test')=='first-install-project-guard';command(['volume','rm',name]);created.remove(name)
try:
 assert agent.project_available(docker,server);checks.append('fresh_unique_project_available')
 name=project+'_pg_data';volume(name);assert not agent.project_available(docker,server);checks.append('unlabelled_existing_database_volume_rejected');remove(name)
 name=project+'_foreign';volume(name,['--label','com.docker.compose.project='+project]);assert not agent.project_available(docker,server);checks.append('labelled_project_volume_rejected');remove(name)
 assert agent.project_available(docker,server);checks.append('only_own_empty_test_volumes_removed')
 out=ROOT/'output/control-install-guard-verification';out.mkdir(parents=True,exist_ok=True);(out/'real-docker-verification.json').write_text(json.dumps({'status':'PASS','scope':'ISOLATED_LOCAL_DOCKER','productionHostsUsed':False,'checks':checks},indent=2)+'\n');print('real Docker project guard passed',len(checks),'checks')
finally:
 for name in created.copy():remove(name)
