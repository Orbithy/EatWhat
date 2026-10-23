#!/usr/bin/env python3
"""One root-owned SG mirror worker. Campus communication is optional: a lost
link pauses mirroring, never local login/readiness and never elects a controller.
Only compact status is printed. Credential/state packets remain captured pipes.
"""
import fcntl,json,os,pathlib,subprocess,sys,time

HELPER='/opt/eatwhat-control/independent/independent-control-peer.py'
ROOT=pathlib.Path('/opt/eatwhat-control/independent')


def invoke(site,operation,**arguments):
    request={'operation':operation,**arguments}
    code="import json,runpy\ng=runpy.run_path("+repr(HELPER)+",run_name='operator_peer');p=g['load']();r=json.loads("+repr(json.dumps(request))+")\nop=r['operation']\n"
    code+="if op=='status':v=p.status()\nelif op=='info':v=p.info()\nelif op=='export':v=p.export()\nelif op=='heartbeat':v=p.heartbeat(r['peer'])\nelif op=='install':v=p.install(r['packet'])\nelif op=='outbox':v=p.outbox()\nelif op=='forward':v=p.forward(r['request'])\nelif op=='acknowledge':v=p.acknowledge(r['result'])\nelse:raise RuntimeError('OPERATION_REFUSED')\nprint(json.dumps(v,separators=(',',':')))\n"
    args=['python3','-']if site=='singapore'else['python3',str(ROOT/'independent-control-relay.py'),'campus']
    result=subprocess.run(args,input=code,text=True,capture_output=True,timeout=45)
    if result.returncode:raise RuntimeError('PEER_UNAVAILABLE')
    return json.loads(result.stdout)


def cycle():
    infos={site:invoke(site,'info')for site in ['singapore','campus']}
    for site,other in [('singapore','campus'),('campus','singapore')]:
        if infos[site]['running']and infos[other]['running']:invoke(site,'heartbeat',peer=infos[other])
    for site,other in [('singapore','campus'),('campus','singapore')]:
        for request in invoke(site,'outbox'):
            result=invoke(other,'forward',request=request)
            invoke(site,'acknowledge',result={'requestId':request['request_id'],**result})
    states={site:invoke(site,'status')for site in infos}
    active=[site for site in infos if infos[site]['running']and states[site]['holder']==infos[site]['controllerId']and states[site]['live']]
    if len(active)>1:raise RuntimeError('TWO_AUTHORITIES_NO_COPY')
    if active:
        source=active[0];target='campus'if source=='singapore'else'singapore'
        packet=invoke(source,'export');result=invoke(target,'install',packet=packet)
        # The source's own process heartbeat has a five-second local TTL.
        # Re-observe it after snapshot replay instead of publishing that short
        # TTL across a slower encrypted transport.
        refreshed=invoke(source,'info');invoke(target,'heartbeat',peer=refreshed)
        return {'state':'MIRRORED','source':source,'generation':states[source]['generation'],'result':result}
    # A handoff has a deliberate no-leader interval. Only its committed source
    # release is eligible; target timeout/expiry never supplies such a proof.
    for source,target in [('campus','singapore'),('singapore','campus')]:
        packet=invoke(source,'export')
        pending=[h for h in packet['tables']['eatwhat_cluster.control_handoff']if h['state']=='TRANSFERRING'and h['source_holder']==infos[source]['controllerId']and h['target_holder']==infos[target]['controllerId']]
        if pending:
            result=invoke(target,'install',packet=packet)
            refreshed=invoke(source,'info');invoke(target,'heartbeat',peer=refreshed)
            return {'state':'RELEASE_INSTALLED','source':source,'target':target,'result':result}
    return {'state':'NO_AUTHORITY_NO_ELECTION'}


def main():
    assert os.geteuid()==0 and sys.platform=='linux'and sys.argv[1:]in [[],['--once']]
    ROOT.mkdir(mode=0o700,exist_ok=True)
    with (ROOT/'sync.lock').open('a+b')as lock:
        os.chmod(ROOT/'sync.lock',0o600);fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        last=None
        while True:
            try:status=cycle()
            except Exception as failure:status={'state':'SYNC_UNAVAILABLE','errorType':type(failure).__name__}
            # Avoid periodic unchanged chatter and never serialize exceptions.
            summary=json.dumps(status,sort_keys=True)
            if summary!=last:print(summary,flush=True);last=summary
            temp=ROOT/'sync-status.tmp';temp.write_text(summary+'\n');temp.chmod(0o600);os.replace(temp,ROOT/'sync-status.json')
            if sys.argv[1:]==['--once']:return
            time.sleep(1)


if __name__=='__main__':main()
