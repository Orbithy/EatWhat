"""Once-only asynchronous composite first-install worker.

Uses the original, separately verified controller/catalog and an embedded fixed
first installer. Installation completion is not a Docker network successor or
cluster JOIN proof. Original private inputs/agents/receipts are never replaced.
"""
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import types
import uuid

FIRST_SOURCE='__EATWHAT_BUSINESS_FIRST_INSTALL_BASE64__'
RELEASE_SOURCE='__EATWHAT_BUSINESS_RELEASE_AGENT_BASE64__'
INITDB_SOURCE='__EATWHAT_BUSINESS_INITDB_BASE64__'
NETWORK_SOURCE='__EATWHAT_BUSINESS_NETWORK_POLICY_BASE64__'
SERVICES_SOURCE='__EATWHAT_BUSINESS_SERVICES_POLICY_BASE64__'
FIREWALL_SOURCE='__EATWHAT_BUSINESS_FIREWALL_POLICY_BASE64__'
PROTOCOL='eatwhat-controller-bootstrap-business-v1'
INSTALL_PROTOCOL='eatwhat-install-v1'
BASE=Path('/var/lib/eatwhat-controller-bootstrap-business')
CONTROLLERS=Path('/var/lib/eatwhat-controller-bootstrap-controller')
CONTROL=Path('/opt/eatwhat-control')
ROOT_UID=ROOT_GID=0
MAX_INPUT=2*1024*1024
_CONTROLLER=_INSTALLER=_NETWORK=_SERVICES=_FIREWALL=None

class Refusal(Exception):
    def __init__(self,code='BOOTSTRAP_BUSINESS_INPUT_INVALID'):self.code=code;super().__init__(code)
def require(value,code='BOOTSTRAP_BUSINESS_INPUT_INVALID'):
    if not value:raise Refusal(code)
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def digest(raw):return hashlib.sha256(raw).hexdigest()
def identifier(value):
    try:require(str(uuid.UUID(value))==value and uuid.UUID(value).int!=0)
    except (ValueError,TypeError,AttributeError):raise Refusal('BOOTSTRAP_BUSINESS_ID_INVALID')
    return value
def phase(id,name):return str(uuid.UUID(bytes=hashlib.md5((identifier(id)+':'+name).encode()).digest(),version=3))
def sha(value):require(isinstance(value,str) and re.fullmatch('[a-f0-9]{64}',value));return value
def directory(path):
    require(all(not p.is_symlink() for p in [path,*path.parents]) and path.is_dir(),'BOOTSTRAP_BUSINESS_UNSAFE_DIRECTORY')
    s=path.stat();require(s.st_uid==ROOT_UID and s.st_gid==ROOT_GID and stat.S_IMODE(s.st_mode)==0o700,'BOOTSTRAP_BUSINESS_UNSAFE_DIRECTORY')
def private(path,maximum=MAX_INPUT):
    require(all(not p.is_symlink() for p in [path,*path.parents]) and path.is_file(),'BOOTSTRAP_BUSINESS_UNSAFE_FILE')
    s=path.stat();require(s.st_uid==ROOT_UID and s.st_gid==ROOT_GID and stat.S_IMODE(s.st_mode)==0o600 and s.st_size<=maximum,'BOOTSTRAP_BUSINESS_UNSAFE_FILE')
    with path.open('rb') as stream:raw=stream.read(maximum+1)
    require(len(raw)<=maximum,'BOOTSTRAP_BUSINESS_UNSAFE_FILE');return raw
def atomic(path,value):
    raw=value if isinstance(value,bytes) else canonical(value);require(not path.is_symlink(),'BOOTSTRAP_BUSINESS_UNSAFE_FILE')
    fd,name=tempfile.mkstemp(prefix='.business-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as stream:os.fchmod(stream.fileno(),0o600);stream.write(raw);stream.flush();os.fsync(stream.fileno())
        os.replace(name,path);fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:os.fsync(fd)
        finally:os.close(fd)
    finally:Path(name).unlink(missing_ok=True)
def task(data):return BASE/data['id']
def header(data):return {k:v for k,v in data.items() if k!='agent'}
def fields(data,operation='INSTALL'):
    return {'protocol':INSTALL_PROTOCOL,'bootstrapProtocol':PROTOCOL,'jobId':data['id'],'binding':digest(canonical(header(data))),
        'operation':operation,'serverId':data['business']['serverId']}
def sources():return {'first-install.py':base64.b64decode(FIRST_SOURCE),'remote-release.py':base64.b64decode(RELEASE_SOURCE),'initdb.sh':base64.b64decode(INITDB_SOURCE),
    'network-policy.py':base64.b64decode(NETWORK_SOURCE),'services-policy.py':base64.b64decode(SERVICES_SOURCE),'firewall-policy.py':base64.b64decode(FIREWALL_SOURCE)}
def validate(data,mode='header'):
    keys={'id','bootstrapId','controllerId','controllerBinding','controllerAgentSha256','approvalDigest','business','firstInstallSha256','releaseAgentSha256','initdbSha256','networkPolicySha256','servicesPolicySha256','networkEffects','firewallPolicySha256','firewallEffects'}
    if mode!='inspect':keys|={'expectedProof','agentSha256','expectedNetworkContextSha256','expectedKernelBaselineSha256','expectedServicesBaselineSha256','expectedFirewallBaselineSha256','expectedIpv6BaselineSha256'}
    if mode=='prepare':keys.add('agent')
    require(isinstance(data,dict) and set(data)==keys)
    require(isinstance(data['business'],dict),'BOOTSTRAP_BUSINESS_CONFIGURATION_INVALID')
    for key in ['id','bootstrapId','controllerId']:identifier(data[key])
    require(data['id']==phase(data['bootstrapId'],'BUSINESS_INSTALL') and data['controllerId']==phase(data['bootstrapId'],'CONTROLLER_CONFIG'))
    for key in ['controllerBinding','controllerAgentSha256','approvalDigest','firstInstallSha256','releaseAgentSha256','initdbSha256','networkPolicySha256','servicesPolicySha256','firewallPolicySha256','expectedNetworkContextSha256','expectedKernelBaselineSha256','expectedServicesBaselineSha256','expectedFirewallBaselineSha256','expectedIpv6BaselineSha256','expectedProof','agentSha256']:
        if key in data:sha(data[key])
    require(len(canonical(data))<=MAX_INPUT,'BOOTSTRAP_BUSINESS_INPUT_TOO_LARGE')
    embedded=sources()
    require(isinstance(data['networkEffects'],dict) and set(data['networkEffects'])=={'allowNewDaemon','allowIpForwardEnable'}
        and all(type(v) is bool for v in data['networkEffects'].values()),'BOOTSTRAP_BUSINESS_NETWORK_SCOPE_INVALID')
    require(isinstance(data['firewallEffects'],dict) and set(data['firewallEffects'])=={'allowForwardPolicyDrop'}
        and type(data['firewallEffects']['allowForwardPolicyDrop']) is bool,'BOOTSTRAP_BUSINESS_NETWORK_SCOPE_INVALID')
    for key,name in [('firstInstallSha256','first-install.py'),('releaseAgentSha256','remote-release.py'),('initdbSha256','initdb.sh'),('networkPolicySha256','network-policy.py'),('servicesPolicySha256','services-policy.py'),('firewallPolicySha256','firewall-policy.py')]:
        require(digest(embedded[name])==data[key],'BOOTSTRAP_BUSINESS_INSTALLER_CHANGED')
    if mode=='prepare':require(isinstance(data['agent'],str) and digest(data['agent'].encode())==data['agentSha256'],'BOOTSTRAP_BUSINESS_AGENT_CHANGED')
    return data
def controller(data,live=False):
    global _CONTROLLER
    path=CONTROLLERS/data['controllerId'];directory(path);prior=json.loads(private(path/'input.json'));source=private(path/'agent.py',524288)
    require(digest(canonical(prior))==data['controllerBinding'] and digest(source)==data['controllerAgentSha256']==prior['agentSha256']
        and prior['id']==data['controllerId'] and prior['verifyId']==phase(data['bootstrapId'],'CONTROLLER_VERIFY')
        and prior['approvalDigest']==data['approvalDigest'],'BOOTSTRAP_BUSINESS_CONTROLLER_CHANGED')
    if _CONTROLLER is None:
        _CONTROLLER=types.ModuleType('original_business_controller');exec(compile(source,'<original-controller>','exec'),_CONTROLLER.__dict__)
    c=_CONTROLLER;c.original(prior,False)
    if live:
        require(c.status(prior,'VERIFY')['state']=='VERIFIED','BOOTSTRAP_BUSINESS_CONTROLLER_NOT_VERIFIED')
        request=c.context(prior)[5]
        require(request['role']=='TARGET' and request['targetServerId']==data['business']['serverId']
            and request['businessAllocationSlot']==data['business']['allocationSlot'],'BOOTSTRAP_BUSINESS_TARGET_CHANGED')
    return c,prior
def installer():
    global _INSTALLER
    if _INSTALLER is None:
        _INSTALLER=types.ModuleType('original_first_install');exec(compile(base64.b64decode(FIRST_SOURCE),'<original-first-install>','exec'),_INSTALLER.__dict__)
    return _INSTALLER
def network_policy():
    global _NETWORK
    if _NETWORK is None:
        _NETWORK=types.ModuleType('original_business_network_policy');exec(compile(base64.b64decode(NETWORK_SOURCE),'<original-business-network-policy>','exec'),_NETWORK.__dict__)
    return _NETWORK
def services_policy():
    global _SERVICES
    if _SERVICES is None:
        _SERVICES=types.ModuleType('original_business_services_policy');exec(compile(base64.b64decode(SERVICES_SOURCE),'<original-business-services-policy>','exec'),_SERVICES.__dict__)
    return _SERVICES
def firewall_policy():
    global _FIREWALL
    if _FIREWALL is None:
        _FIREWALL=types.ModuleType('original_business_firewall_policy');exec(compile(base64.b64decode(FIREWALL_SOURCE),'<original-business-firewall-policy>','exec'),_FIREWALL.__dict__)
    return _FIREWALL
def network_context(data):
    n=network_policy();s=services_policy()
    try:
        c,parent=controller(data,True);value=n.capture_context();value['controlServices']=s.freeze(c,parent);return value
    except (n.Refusal,s.Refusal) as failure:raise Refusal(failure.code) from None
    except Exception:raise Refusal('BOOTSTRAP_BUSINESS_NETWORK_CONTEXT_UNAVAILABLE') from None
def payload(data):
    c,parent=controller(data);b=data['business'];keys={'serverId','jobId','siteId','instanceId','mode','clusterId','allocationSlot','artifactSha256','artifactBytes','httpPort','appBaseUrl','installDocker','database','nodeToken','properties','images'}
    if 'imageArchiveBytes' in b:keys|={'imageArchiveBytes','imageArchiveSha256'}
    require(isinstance(b,dict) and set(b)==keys,'BOOTSTRAP_BUSINESS_CONFIGURATION_INVALID')
    for key in ['serverId','jobId','instanceId','clusterId']:identifier(b[key])
    require(b['jobId']==data['id'] and b['mode']=='EXTERNAL' and b['siteId']=='server-'+uuid.UUID(b['serverId']).hex[:12]
        and b['instanceId']==str(uuid.UUID(bytes=hashlib.md5((data['bootstrapId']+':BUSINESS_INSTANCE').encode()).digest(),version=3))
        and type(b['allocationSlot']) is int and 1<=b['allocationSlot']<=1023,'BOOTSTRAP_BUSINESS_IDENTITY_INVALID')
    require(b['clusterId']==parent['sourceAuthority']['clusterId'] and type(b['httpPort']) is int and 1024<=b['httpPort']<=65535
        and b['httpPort'] not in [18080,18081] and type(b['installDocker']) is bool,'BOOTSTRAP_BUSINESS_CONFIGURATION_INVALID')
    cat=c.policy().catalog(parent['bundle']);require(cat is not None,'BOOTSTRAP_BUSINESS_CATALOG_REQUIRED')
    require(b['artifactSha256']==cat['businessSha256'] and b['artifactBytes']==cat['businessSize'] and type(b['artifactBytes']) is int
        and b['images']=={'app':cat['images']['jre'],'postgres':cat['images']['postgres'],'redis':cat['images']['redis']},'BOOTSTRAP_BUSINESS_ARTIFACT_CHANGED')
    archive=cat['imageArchive'];require((archive is None)==('imageArchiveBytes' not in b),'BOOTSTRAP_BUSINESS_ARTIFACT_CHANGED')
    if archive is not None:require(type(b['imageArchiveBytes']) is int and b['imageArchiveBytes']==archive['size'] and b['imageArchiveSha256']==archive['sha256'],'BOOTSTRAP_BUSINESS_ARTIFACT_CHANGED')
    db=b['database'];require(isinstance(db,dict) and set(db)=={'POSTGRES_USER','POSTGRES_PASSWORD','PG_DB','PG_APP_USER','PG_APP_PASSWORD','REDIS_PASSWORD'})
    require(all(isinstance(v,str) and re.fullmatch('[A-Za-z0-9_-]{1,128}',v) for v in db.values()) and db['POSTGRES_USER']=='eatwhat_migration' and db['PG_APP_USER']=='eatwhat_app' and db['PG_DB']=='eatwhat')
    require(isinstance(b['nodeToken'],str) and re.fullmatch('[A-Za-z0-9_-]{43,128}',b['nodeToken']))
    p=b['properties'];require(isinstance(p,dict));required={'app.site.id':b['siteId'],'app.site.instance-id':b['instanceId'],'app.site.mode':'EXTERNAL','app.site.cluster-id':b['clusterId'],
        'app.site.allocation-slot':str(b['allocationSlot']),'app.control.enrollment-required':'true','app.cluster.enabled':'false','app.account.enabled':'false','app.sync.transport-enabled':'false',
        'app.sync.capture-enabled':'false','app.control.deployment.enabled':'false','app.storage.files.replication.worker-enabled':'false','app.storage.files.gc.worker-enabled':'false',
        'app.control.node-token-file':'/config/control.token','app.control.state-directory':'/state','server.port':'8080','app.base-url':b['appBaseUrl'],
        'spring.datasource.url':'jdbc:postgresql://postgres:5432/eatwhat','spring.datasource.username':db['PG_APP_USER'],'spring.datasource.password':db['PG_APP_PASSWORD'],
        'spring.flyway.url':'jdbc:postgresql://postgres:5432/eatwhat','spring.flyway.user':db['POSTGRES_USER'],'spring.flyway.password':db['POSTGRES_PASSWORD'],'spring.flyway.enabled':'true',
        'spring.flyway.clean-disabled':'true','spring.flyway.validate-on-migrate':'true','spring.data.redis.host':'redis','spring.data.redis.port':'6379','spring.data.redis.database':'0','spring.data.redis.password':db['REDIS_PASSWORD']}
    require(all(p.get(k)==v for k,v in required.items()) and p.get('app.cluster.auth-store.enabled','false')=='false','BOOTSTRAP_BUSINESS_CONFIGURATION_INVALID')
    from urllib.parse import urlsplit
    url=urlsplit(b['appBaseUrl']);require(url.scheme in ['http','https'] and url.hostname and url.username is None and not url.query and not url.fragment and len(b['appBaseUrl'])<=512)
    rendered=c.policy().properties_bytes(p);require(len(rendered)<=65536,'BOOTSTRAP_BUSINESS_CONFIGURATION_TOO_LARGE')
    value={k:v for k,v in b.items() if k!='appBaseUrl'};value['properties']=rendered.decode('ascii');value['releaseAgent']=base64.b64decode(RELEASE_SOURCE).decode();value['initdb']=base64.b64decode(INITDB_SOURCE).decode();return value
def assets(data):
    c,parent=controller(data);prior=c.context(parent)[4];specs=c.policy().artifact_specs(parent['bundle']);b=data['business'];result=[('BUSINESS',b['artifactBytes'],b['artifactSha256'])]
    if 'imageArchiveBytes' in b:result.append(('IMAGES',b['imageArchiveBytes'],b['imageArchiveSha256']))
    paths=[]
    for kind,size,sha256 in result:
        spec=specs[kind];require(spec['sha256']==sha256 and spec['size']==size,'BOOTSTRAP_BUSINESS_ARTIFACT_CHANGED');path=CONTROL/'acceptance'/spec['filename']
        require(all(not p.is_symlink() for p in [path,*path.parents]) and path.is_file(),'BOOTSTRAP_BUSINESS_ARTIFACT_CHANGED')
        info=path.stat();require(info.st_size==size and info.st_uid==prior['ownerUid'] and info.st_gid==prior['ownerGid']
            and stat.S_IMODE(info.st_mode)==0o600,'BOOTSTRAP_BUSINESS_ARTIFACT_CHANGED')
        value=hashlib.sha256()
        with path.open('rb') as stream:
            while block:=stream.read(65536):value.update(block)
        require(value.hexdigest()==sha256,'BOOTSTRAP_BUSINESS_ARTIFACT_CHANGED');paths.append(path)
    return paths
def inspect(data):
    validate(data,'inspect');controller(data,True);b=payload(data);assets(data);checked=installer().inspect(b)
    require(checked['rootAvailable'] and checked['portAvailable'] and checked['rootUser'] and checked['diskFreeMiB']>=3072
        and (checked['projectAvailable'] is True or not checked['dockerReady'] and checked['projectAvailable'] is None)
        and (checked['dockerReady'] or b['installDocker'] and checked['canInstallDocker']),'BOOTSTRAP_BUSINESS_TARGET_RETAINED_OR_UNREADY')
    context=network_context(data);snapshot=context['snapshot']
    require(snapshot['daemonId'] is not None or data['networkEffects']['allowNewDaemon'] and data['business']['installDocker']
        and (snapshot['ipForward']==1 or data['networkEffects']['allowIpForwardEnable']) and data['firewallEffects']['allowForwardPolicyDrop'],'BOOTSTRAP_BUSINESS_NETWORK_SCOPE_REQUIRED')
    facts={key:value for key,value in checked.items() if key!='diskFreeMiB'};facts['minimumDiskSatisfied']=True
    context_sha=digest(canonical(context));snapshot_sha=digest(canonical(snapshot))
    return {**fields(data,'INSPECT'),'effectsStarted':False,'proof':digest(canonical({'header':data,'facts':facts,'networkContextSha256':context_sha})),
        'networkContextSha256':context_sha,'kernelBaselineSha256':snapshot_sha,'servicesBaselineSha256':digest(canonical(context['controlServices'])),
        'firewallBaselineSha256':digest(canonical(context['firewall'])),'ipv6BaselineSha256':digest(canonical(context['ipv6']))}
def inspection_matches(data):
    seen=inspect(inspect_input(data))
    return seen['proof']==data['expectedProof'] and all(seen[key]==data[expected] for key,expected in [('networkContextSha256','expectedNetworkContextSha256'),
        ('kernelBaselineSha256','expectedKernelBaselineSha256'),('servicesBaselineSha256','expectedServicesBaselineSha256'),('firewallBaselineSha256','expectedFirewallBaselineSha256'),('ipv6BaselineSha256','expectedIpv6BaselineSha256')])
def capture_baseline(data):
    context=network_context(data)
    require(digest(canonical(context))==data['expectedNetworkContextSha256'] and digest(canonical(context['snapshot']))==data['expectedKernelBaselineSha256'] and digest(canonical(context['controlServices']))==data['expectedServicesBaselineSha256'],
        'BOOTSTRAP_BUSINESS_NETWORK_APPROVAL_EXPIRED')
    require(digest(canonical(context['firewall']))==data['expectedFirewallBaselineSha256'] and digest(canonical(context['ipv6']))==data['expectedIpv6BaselineSha256'],
        'BOOTSTRAP_BUSINESS_NETWORK_APPROVAL_EXPIRED')
    value={'protocol':'eatwhat-business-network-baseline-v1','jobId':data['id'],'bootstrapId':data['bootstrapId'],'inputBinding':fields(data)['binding'],
        'approvalDigest':data['approvalDigest'],'policySha256':data['networkPolicySha256'],'context':context}
    path=task(data)/'network-before.json';require(not path.exists() and not path.is_symlink(),'BOOTSTRAP_BUSINESS_NETWORK_BASELINE_RETAINED');atomic(path,value)
    return digest(canonical(value))
def baseline(data,saved):
    raw=private(task(data)/'network-before.json');value=json.loads(raw)
    require(digest(raw)==saved.get('networkBaselineSha256') and set(value)=={'protocol','jobId','bootstrapId','inputBinding','approvalDigest','policySha256','context'}
        and value['protocol']=='eatwhat-business-network-baseline-v1' and value['jobId']==data['id'] and value['bootstrapId']==data['bootstrapId']
        and value['inputBinding']==fields(data)['binding'] and value['approvalDigest']==data['approvalDigest'] and value['policySha256']==data['networkPolicySha256']
        and digest(canonical(value['context']))==data['expectedNetworkContextSha256']
        and digest(canonical(value['context']['snapshot']))==data['expectedKernelBaselineSha256']
        and digest(canonical(value['context']['controlServices']))==data['expectedServicesBaselineSha256'],'BOOTSTRAP_BUSINESS_NETWORK_BASELINE_UNCONFIRMED')
    require(digest(canonical(value['context']['firewall']))==data['expectedFirewallBaselineSha256'] and digest(canonical(value['context']['ipv6']))==data['expectedIpv6BaselineSha256'],
        'BOOTSTRAP_BUSINESS_NETWORK_BASELINE_UNCONFIRMED')
    return value
def kernel_verification(data,result,before):
    n=network_policy();b=data['business'];raw=private(Path('/opt/eatwhat-sites')/b['serverId']/'private/created-network.json',4096);created=json.loads(raw)
    require(set(created)=={'protocol','serverId','jobId','networkId','project'} and created['protocol']=='eatwhat-created-business-network-v1'
        and created['serverId']==b['serverId'] and created['jobId']==data['id'] and created['project']=='eatwhat-site-'+b['serverId'][:8]
        and created['networkId']==result.get('createdNetworkId') and digest(raw)==result.get('createdNetworkProofSha256'),'BOOTSTRAP_BUSINESS_NETWORK_CREATION_UNCONFIRMED')
    q={'serverId':b['serverId'],'jobId':data['id'],'approvalDigest':data['approvalDigest'],'inputBinding':fields(data)['binding'],'expectedNetworkId':created['networkId'],**data['networkEffects']}
    try:
        graph=n.observe_kernel_graph(q);now=n.snapshot(graph);proof=n.verify(q,before['context']['snapshot'],now,graph['containers'])
    except n.Refusal as failure:raise Refusal(failure.code) from None
    except Exception:raise Refusal('BOOTSTRAP_BUSINESS_NETWORK_OBSERVATION_UNAVAILABLE') from None
    return {'kernelIpv4DeltaVerified':True,'kernelNetworkProof':proof}
def services_verification(data,before):
    s=services_policy();q={'serverId':data['business']['serverId'],'jobId':data['id'],'approvalDigest':data['approvalDigest'],
        'inputBinding':fields(data)['binding'],'controllerBinding':data['controllerBinding']}
    try:proof=s.verify(q,before['context']['controlServices'])
    except s.Refusal as failure:raise Refusal(failure.code) from None
    except Exception:raise Refusal('BOOTSTRAP_BUSINESS_SERVICES_OBSERVATION_UNAVAILABLE') from None
    return {'controllerServicesRevalidated':True,'controllerServicesProof':proof}
def firewall_verification(data,result,before):
    n=network_policy();f=firewall_policy();q={'serverId':data['business']['serverId'],'jobId':data['id'],'approvalDigest':data['approvalDigest'],
        'inputBinding':fields(data)['binding'],'expectedNetworkId':result['createdNetworkId'],**data['networkEffects']}
    try:
        first={'firewall':n.firewall_context(),'ipv6':n.ipv6_context()};graph=n.observe_kernel_graph(q);now=n.snapshot(graph)
        kernel=n.verify(q,before['context']['snapshot'],now,graph['containers'])
        last={'firewall':n.firewall_context(),'ipv6':n.ipv6_context()}
        require(canonical(first)==canonical(last),'BOOTSTRAP_BUSINESS_NETWORK_CHANGED_DURING_OBSERVATION')
        own=graph['dockerNetworks'][q['expectedNetworkId']];bridge,subnet,gateway=n.network_descriptor(own)
        app=next(c for c in graph['containers'] if c['Labels']['com.docker.compose.service']=='app')
        ports={'8080/tcp':[{'HostIp':'127.0.0.1','HostPort':str(data['business']['httpPort'])}]}
        require(app['Ports']==ports and app['PortBindings']==ports and all(not any(c['Ports'].values()) and not c['PortBindings'] for c in graph['containers'] if c is not app),
            'BOOTSTRAP_BUSINESS_FIREWALL_PORT_BINDING_CHANGED')
        fresh=before['context']['snapshot']['daemonId'] is None;default=None
        if fresh:default=str(n.network_descriptor(next(v for v in graph['dockerNetworks'].values() if v['Name']=='bridge'),True)[1])
        d={'bridge':bridge,'subnet':str(subnet),'containerIps':[p['IPv4Address'].split('/')[0] for p in own['Containers'].values()],
            'appIp':app['Networks'][own['Name']]['IPAddress'],'httpPort':data['business']['httpPort'],'freshDaemon':fresh,'defaultSubnet':default,**data['firewallEffects']}
        proof=f.verify_firewall(d,before['context']['firewall'],last['firewall'])
        ipv6=f.verify_ipv6(kernel['addedInterfaces'],graph['network']['links'],before['context']['ipv6'],last['ipv6'])
        binding={k:q[k] for k in ['serverId','jobId','approvalDigest','inputBinding']};binding['ownedNetworkId']=q['expectedNetworkId']
        return {'firewallDeltaVerified':True,'firewallProof':{**proof,**binding},'ipv6DeltaVerified':True,'ipv6Proof':{**ipv6,**binding}}
    except (n.Refusal,f.Refusal) as failure:raise Refusal(failure.code) from None
    except Refusal:raise
    except Exception:raise Refusal('BOOTSTRAP_BUSINESS_FIREWALL_OBSERVATION_UNAVAILABLE') from None
def finalized(data,value,saved):
    result=installer_result(data,value)
    if result['state']=='SUCCEEDED':
        before=baseline(data,saved);result.update(kernel_verification(data,result,before));result.update(services_verification(data,before))
        result.update(firewall_verification(data,result,before))
        result['networkBaselineSha256']=saved['networkBaselineSha256'];result['installationState']='SUCCEEDED'
    return result
def verified_saved(data,saved):
    baseline(data,saved);p=saved.get('kernelNetworkProof');b=data['business']
    require(saved.get('kernelIpv4DeltaVerified') is True and isinstance(p,dict),'BOOTSTRAP_BUSINESS_NETWORK_BASELINE_UNCONFIRMED')
    require(p.get('protocol')=='eatwhat-business-kernel-network-v1' and p.get('state')=='KERNEL_IPV4_DELTA_VERIFIED'
        and p.get('serverId')==b['serverId'] and p.get('jobId')==data['id'] and p.get('approvalDigest')==data['approvalDigest']
        and p.get('inputBinding')==fields(data)['binding'] and p.get('baselineSha256')==data['expectedKernelBaselineSha256']
        and p.get('ownedNetworkId')==saved.get('createdNetworkId') and saved.get('installationState')=='SUCCEEDED',
        'BOOTSTRAP_BUSINESS_NETWORK_BASELINE_UNCONFIRMED')
    for k in ['kernelIpv4DeltaVerified','ipv4DefaultRoutePreserved','dnsPreserved','ipv6RevalidationRequired','privateLinkRevalidationRequired','firewallRevalidationRequired']:
        require(p.get(k) is True,'BOOTSTRAP_BUSINESS_NETWORK_BASELINE_UNCONFIRMED')
    require(p.get('businessHealthVerified') is False and p.get('joinAuthorized') is False,'BOOTSTRAP_BUSINESS_NETWORK_BASELINE_UNCONFIRMED')
    for v in [p.get('ownedNetworkId'),p.get('observationSha256'),saved.get('createdNetworkProofSha256')]:sha(v)
    service=saved.get('controllerServicesProof');require(saved.get('controllerServicesRevalidated') is True and isinstance(service,dict)
        and service.get('controllerBinding')==data['controllerBinding'] and service.get('inputBinding')==fields(data)['binding']
        and service.get('jobId')==data['id'] and service.get('serverId')==b['serverId'] and service.get('approvalDigest')==data['approvalDigest']
        and service.get('protocol')=='eatwhat-business-controller-services-v1' and service.get('state')=='CONTROLLER_SERVICES_REVALIDATED'
        and service.get('baselineSha256')==data['expectedServicesBaselineSha256'] and service.get('controllerServicesRevalidated') is True,
        'BOOTSTRAP_BUSINESS_SERVICES_UNCONFIRMED')
    for key in ['boundedNfsIdentityVerified','mountGuardVerified','privateSshMasterVerified','anonymousAdminDenied','readOnlyAuthorityDatabaseVerified',
        'currentControllerLeaseLive','firewallRevalidationRequired','ipv6RevalidationRequired']:
        require(service.get(key) is True,'BOOTSTRAP_BUSINESS_SERVICES_UNCONFIRMED')
    require(service.get('controllerReadiness')=='UP' and service.get('businessHealthVerified') is False and service.get('joinAuthorized') is False,
        'BOOTSTRAP_BUSINESS_SERVICES_UNCONFIRMED');sha(service.get('observationSha256'))
    for prefix,expected in [('firewall','expectedFirewallBaselineSha256'),('ipv6','expectedIpv6BaselineSha256')]:
        proof=saved.get(prefix+'Proof');require(saved.get(prefix+'DeltaVerified') is True and isinstance(proof,dict)
            and proof.get('protocol')=='eatwhat-business-'+prefix+'-v1' and proof.get('state')==('FIREWALL_DELTA_VERIFIED' if prefix=='firewall' else 'IPV6_DELTA_VERIFIED')
            and proof.get('jobId')==data['id'] and proof.get('serverId')==b['serverId'] and proof.get('approvalDigest')==data['approvalDigest']
            and proof.get('inputBinding')==fields(data)['binding'] and proof.get('ownedNetworkId')==saved.get('createdNetworkId')
            and proof.get('baselineSha256')==data[expected] and proof.get(prefix+'DeltaVerified') is True and proof.get('joinAuthorized') is False,
            'BOOTSTRAP_BUSINESS_FIREWALL_UNCONFIRMED');sha(proof.get('observationSha256'))
        for k in (['originalRulesPreserved'] if prefix=='firewall' else ['originalAddressesAndRoutesPreserved','originalSysctlsPreserved']):
            require(proof.get(k) is True,'BOOTSTRAP_BUSINESS_FIREWALL_UNCONFIRMED')
    return saved
def lock(create=False):
    if create and not BASE.exists():BASE.mkdir(mode=0o700)
    directory(BASE);path=BASE/'host.lock'
    if create and not path.exists():
        fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600);os.close(fd)
    private(path);stream=path.open('r+')
    try:fcntl.lockf(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except Exception:stream.close();raise Refusal('BOOTSTRAP_BUSINESS_HOST_BUSY')
    return stream
def original(data):
    validate(data);directory(BASE);reserve=json.loads(private(BASE/'reservation.json'))
    require(reserve=={'id':data['id'],'binding':fields(data)['binding']},'BOOTSTRAP_BUSINESS_HOST_RESERVED');directory(task(data))
    require(json.loads(private(task(data)/'input.json'))==data and digest(private(task(data)/'agent.py',524288))==data['agentSha256'],'BOOTSTRAP_BUSINESS_AGENT_CHANGED')
    for name,raw in sources().items():require(private(task(data)/name,524288)==raw,'BOOTSTRAP_BUSINESS_INSTALLER_CHANGED')
def prepare_status(data):
    validate(data);result=fields(data,'PREPARE')
    if not BASE.exists() and not BASE.is_symlink():return {**result,'state':'NOT_PREPARED','preparationEffectsStarted':False}
    directory(BASE)
    if not (BASE/'reservation.json').exists():
        empty=all(p.name=='host.lock' for p in BASE.iterdir());return {**result,'state':'NOT_PREPARED' if empty else 'OUTCOME_UNKNOWN','preparationEffectsStarted':not empty}
    original(data);return {**result,'state':'PREPARED','preparationEffectsStarted':True}
def inspect_input(data):return {k:v for k,v in data.items() if k not in ['agent','agentSha256','expectedProof','expectedNetworkContextSha256','expectedKernelBaselineSha256','expectedServicesBaselineSha256','expectedFirewallBaselineSha256','expectedIpv6BaselineSha256']}
def prepare(data):
    validate(data,'prepare');h=header(data)
    if BASE.exists():
        with lock(True):
            if (BASE/'reservation.json').exists():original(h);return prepare_status(h)
            require(all(p.name=='host.lock' for p in BASE.iterdir()),'BOOTSTRAP_BUSINESS_PREPARATION_RETAINED')
    require(inspection_matches(h),'BOOTSTRAP_BUSINESS_APPROVAL_EXPIRED')
    with lock(True):
        require(not (BASE/'reservation.json').exists(),'BOOTSTRAP_BUSINESS_HOST_RESERVED');task(h).mkdir(mode=0o700)
        atomic(task(h)/'input.json',h);atomic(task(h)/'agent.py',data['agent'].encode())
        for name,raw in sources().items():atomic(task(h)/name,raw)
        atomic(BASE/'reservation.json',{'id':h['id'],'binding':fields(h)['binding']})
    return prepare_status(h)
def receipt(data):
    path=task(data)/'install-receipt.json'
    if not path.exists() and not path.is_symlink():return None
    value=json.loads(private(path));require(all(value.get(k)==v for k,v in fields(data).items()),'BOOTSTRAP_BUSINESS_RECEIPT_CHANGED');return value
def installer_result(data,value):
    b=data['business'];required={'protocol':INSTALL_PROTOCOL,'serverId':b['serverId'],'jobId':data['id'],'siteId':b['siteId'],'instanceId':b['instanceId'],
        'mode':'EXTERNAL','clusterId':b['clusterId'],'allocationSlot':b['allocationSlot'],'artifactSha256':b['artifactSha256'],'root':'/opt/eatwhat-sites/'+b['serverId']}
    require(isinstance(value,dict) and all(value.get(k)==v for k,v in required.items()) and value.get('state') in ['SUCCEEDED','FAILED'],'BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED')
    if value['state']=='SUCCEEDED':require(value.get('restoreEvidenceId')=='fresh-install-restore-'+data['id'] and value.get('restoreCleanupVerified') is True
        and value.get('httpPort')==b['httpPort'] and value.get('clusterMembership')=='NOT_JOINED','BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED')
    allowed={'state','stage','code','retryable','httpPort','restoreEvidenceId','restoreCleanupVerified','clusterMembership','services','images','lockProof','createdNetworkId','createdNetworkProofSha256'}
    return {**fields(data),**required,**{k:v for k,v in value.items() if k in allowed},'effectsStarted':True,
        'priorNetworkRevalidationRequired':True,'businessHealthRevalidated':False}
def status(data):
    original(data);saved=receipt(data)
    if saved is None:return {**fields(data),'state':'NOT_STARTED','effectsStarted':False}
    if saved['state']=='SUCCEEDED':return verified_saved(data,saved)
    if saved['state']=='FAILED':return saved
    # No old private-peer proof is reinterpreted after Docker changes the host.
    # This only observes the original install journal; later phases need an exact
    # owned-network successor and independent health verification.
    try:
        seen=installer().status({'serverId':data['business']['serverId'],'jobId':data['id']})
        if seen.get('state') in ['SUCCEEDED','FAILED']:
            return {**saved,**finalized(data,seen,saved),'derivedFromOriginalInstallJournal':True}
    except Exception:pass
    return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED'}
def dispatch(data):
    original(data);previous=status(data)
    if previous['state']!='NOT_STARTED':return previous
    require(inspection_matches(data),'BOOTSTRAP_BUSINESS_APPROVAL_EXPIRED')
    with lock():
        if receipt(data) is not None:return status(data)
        saved={**fields(data),'state':'ACCEPTED','effectsStarted':False,'acceptedAt':time.time()};atomic(task(data)/'install-receipt.json',saved)
    try:
        subprocess.run(['systemd-run','--quiet','--no-block','--unit=eatwhat-business-'+data['id'],'--property=Type=exec','--property=UMask=0077',
            '--property=RuntimeMaxSec=3600','--property=StandardOutput=null','--property=StandardError=null','python3',str(task(data)/'agent.py'),'run',data['id']],
            stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=15,check=True)
    except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_BUSINESS_DISPATCH_UNCONFIRMED'}
    return saved
def run_installer(data):
    b=payload(data);paths=assets(data)
    # File-backed stdout bounds diagnostics without exposing private input or
    # buffering a large JAR/image archive in either manager or worker memory.
    with tempfile.TemporaryFile() as output:
        process=subprocess.Popen(['python3',str(task(data)/'first-install.py'),'install'],stdin=subprocess.PIPE,stdout=output,stderr=subprocess.DEVNULL,
            cwd='/',env={'PATH':'/usr/local/bin:/usr/bin:/bin','LANG':'C.UTF-8','HOME':'/root','DOCKER_HOST':'unix:///var/run/docker.sock'})
        try:
            process.stdin.write(canonical(b)+b'\n')
            for path in paths:
                with path.open('rb') as stream:
                    while block:=stream.read(65536):process.stdin.write(block)
            process.stdin.close();require(process.wait(timeout=3300)==0,'BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED')
            require(output.tell()<=65536,'BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED');output.seek(0);return json.loads(output.read(65537))
        finally:
            if process.poll() is None:process.terminate()
def perform(id):
    identifier(id);data=json.loads(private(BASE/id/'input.json'));original(data)
    with lock():
        saved=receipt(data);require(saved is not None and saved['state']=='ACCEPTED','BOOTSTRAP_BUSINESS_ALREADY_DISPATCHED')
        try:
            require(inspection_matches(data),'BOOTSTRAP_BUSINESS_APPROVAL_EXPIRED')
            saved['networkBaselineSha256']=capture_baseline(data);saved.update(state='RUNNING',effectsStarted=True);atomic(task(data)/'install-receipt.json',saved)
            value=installer_result(data,run_installer(data));atomic(task(data)/'original-install-result.json',value)
            saved.update(installationState=value['state'])
            if value['state']=='SUCCEEDED':
                saved.update(stage='KERNEL_NETWORK_OBSERVATION');atomic(task(data)/'install-receipt.json',saved)
            value=finalized(data,value,saved);saved.update(value,finishedAt=time.time())
        except Refusal as failure:
            saved.update(state='OUTCOME_UNKNOWN' if saved['effectsStarted'] else 'FAILED',code=failure.code)
        except Exception:saved.update(state='OUTCOME_UNKNOWN',code='BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED')
        atomic(task(data)/'install-receipt.json',saved);return saved
def main():
    os.umask(0o077)
    try:
        require(os.geteuid()==ROOT_UID and sys.platform=='linux' and len(sys.argv)>=2)
        command=sys.argv[1]
        if command=='run':require(len(sys.argv)==3);result=perform(sys.argv[2])
        else:
            require(len(sys.argv)==2);raw=sys.stdin.buffer.read(MAX_INPUT+1);require(len(raw)<=MAX_INPUT);data=json.loads(raw)
            if command=='inspect':result=inspect(data)
            elif command=='prepare':result=prepare(data)
            elif command=='prepare-status':result=prepare_status(data)
            elif command=='dispatch':result=dispatch(data)
            elif command=='status':result=status(data)
            else:raise Refusal()
        print(json.dumps(result,separators=(',',':')))
    except Refusal as failure:print(json.dumps({'protocol':PROTOCOL,'code':failure.code}));sys.exit(1)
    except Exception:print(json.dumps({'protocol':PROTOCOL,'code':'BOOTSTRAP_BUSINESS_OBSERVATION_UNAVAILABLE'}));sys.exit(1)
if __name__=='__main__':main()
