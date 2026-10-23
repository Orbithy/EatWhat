"""Exact Docker/kernel network delta policy and read-only Linux observations.

Callers must authenticate the before snapshot under the original approval before
any Docker effect. This module does not approve its own inputs, change a host,
reinterpret an old WireGuard receipt or authorize JOIN. Firewall, shared-state,
dependency and current application health need independent successor checks.
"""
from collections import Counter
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import pwd
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid

PROTOCOL='eatwhat-business-kernel-network-v1'
SERVER_LABEL='you.v50to.eatwhat.server-id'
JOB_LABEL='you.v50to.eatwhat.install-job-id'
LABELS=[SERVER_LABEL,JOB_LABEL,'com.docker.compose.project','com.docker.compose.service',
    'com.docker.compose.network','com.docker.compose.project.config_files']
ENV={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LANG':'C.UTF-8','HOME':'/root',
    'DOCKER_HOST':'unix:///var/run/docker.sock','DOCKER_CONFIG':'/var/empty/eatwhat-bootstrap-no-docker-config'}
ROUTE_KEYS={'dst','dev','gateway','prefsrc','table','type','scope','protocol','metric'}

class Refusal(Exception):
    def __init__(self,code='BOOTSTRAP_BUSINESS_NETWORK_INVALID'):self.code=code;super().__init__(code)
def require(value,code='BOOTSTRAP_BUSINESS_NETWORK_INVALID'):
    if not value:raise Refusal(code)
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def digest(value):return hashlib.sha256(canonical(value)).hexdigest()
def identifier(value):
    try:require(isinstance(value,str) and str(uuid.UUID(value))==value and uuid.UUID(value).int!=0)
    except (TypeError,ValueError,AttributeError):raise Refusal()
    return value
def hexadecimal(value):require(isinstance(value,str) and re.fullmatch('[a-f0-9]{64}',value));return value
def interface(value):require(isinstance(value,str) and re.fullmatch('[A-Za-z0-9_.:-]{1,15}',value));return value
def integer(value,low=1,high=2147483647):require(type(value) is int and low<=value<=high);return value
def project(request):return 'eatwhat-site-'+identifier(request['serverId'])[:8]
def request(data):
    require(isinstance(data,dict) and set(data)=={'serverId','jobId','approvalDigest','inputBinding','expectedNetworkId','allowNewDaemon','allowIpForwardEnable'})
    identifier(data['serverId']);identifier(data['jobId']);hexadecimal(data['approvalDigest']);hexadecimal(data['inputBinding']);hexadecimal(data['expectedNetworkId'])
    require(type(data['allowNewDaemon']) is bool and type(data['allowIpForwardEnable']) is bool);return data
def run(args):
    require(isinstance(args,list) and all(isinstance(a,str) for a in args))
    with tempfile.TemporaryFile() as output:
        subprocess.run(args,env=ENV,stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.DEVNULL,timeout=10,check=True)
        require(output.tell()<=2097152,'BOOTSTRAP_BUSINESS_NETWORK_OBSERVATION_TOO_LARGE');output.seek(0);return output.read(2097153)
def executable(name):
    value=shutil.which(name,path=ENV['PATH']);require(value is not None,'BOOTSTRAP_BUSINESS_NETWORK_TOOL_UNAVAILABLE');path=Path(value).resolve();info=path.stat()
    require(path.is_file() and info.st_uid==0 and info.st_mode&0o022==0,'BOOTSTRAP_BUSINESS_NETWORK_TOOL_UNAVAILABLE')
    # Preserve argv[0] for distribution multicall symlinks such as
    # iptables-save -> xtables-nft-multi; validate the resolved file only.
    return value
def json_command(args):return json.loads(run(args))
def normalize_network(addresses,routes,links):
    require(isinstance(addresses,list) and isinstance(routes,list) and isinstance(links,list) and len(addresses)<=512 and len(routes)<=4096 and len(links)<=512)
    indexes={interface(row['ifname']):integer(row['ifindex']) for row in links};require(len(indexes)==len(links) and len(set(indexes.values()))==len(links))
    link_map={}
    for row in links:
        name=interface(row['ifname']);master=row.get('master');require(master is None or isinstance(master,str) and master in indexes or type(master) is int and master in indexes.values())
        peer=row.get('link_index');require(peer is None or type(peer) is int and peer>0)
        value={'ifindex':indexes[name],'kind':row.get('linkinfo',{}).get('info_kind',row.get('link_type')),
            'master':indexes[master] if isinstance(master,str) else master,'peerIfindex':peer,'mtu':integer(row['mtu']),
            'mac':row.get('address'),'flags':sorted(row.get('flags',[]))}
        link_map[name]=value
    stable=[]
    for row in addresses:
        name=interface(row['ifname']);require(name in indexes)
        require(row['ifindex']==indexes[name] and sorted(row.get('flags',[]))==link_map[name]['flags'],'BOOTSTRAP_BUSINESS_NETWORK_CHANGED_DURING_OBSERVATION')
        values=[{k:a[k] for k in ['local','prefixlen','scope'] if k in a} for a in row.get('addr_info',[])]
        stable.append({'ifname':name,'flags':sorted(row.get('flags',[])),'addresses':values})
    present={r['ifname'] for r in stable};require(len(present)==len(stable))
    # ip -4 address omits links without an IPv4 address. Keep every kernel link
    # from the unfiltered link dump; an unaddressed foreign veth must still be
    # rejected by exact ownership, never disappear from the comparison.
    stable.extend({'ifname':name,'flags':link_map[name]['flags'],'addresses':[]} for name in indexes if name not in present)
    return {'addresses':stable,'routes':[{k:v for k,v in row.items() if k in ROUTE_KEYS} for row in routes],'links':link_map}
def network():
    ip=executable('ip');return normalize_network(json_command([ip,'-j','-4','address','show']),
        json_command([ip,'-j','-4','route','show','table','all']),json_command([ip,'-j','-d','link','show']))
def labels(value):return {k:v for k,v in (value or {}).items() if k in LABELS}
def normalize_docker_network(row):
    hexadecimal(row['Id']);require(isinstance(row.get('Containers',{}),dict))
    return {k:row.get(k) for k in ['Id','Name','Driver','Scope','EnableIPv6','Internal','Ingress','ConfigOnly']}|{
        'Labels':labels(row.get('Labels')),'Options':row.get('Options') or {},'IPAM':row.get('IPAM'),
        'Containers':{key:{k:v.get(k,'') for k in ['EndpointID','MacAddress','IPv4Address','IPv6Address']} for key,v in row.get('Containers',{}).items()}}
def docker_networks():
    docker=executable('docker');ids=run([docker,'network','ls','--no-trunc','--format','{{.ID}}']).decode().splitlines()
    require(0<len(ids)<=128 and len(set(ids))==len(ids));[hexadecimal(v) for v in ids]
    values=json_command([docker,'network','inspect',*ids]);require(len(values)==len(ids) and {r['Id'] for r in values}==set(ids))
    return {r['Id']:normalize_docker_network(r) for r in values}
def namespace_identity(pid):
    integer(pid,2);proc=Path('/proc')/str(pid);raw=(proc/'stat').read_text();end=raw.rfind(')');require(end>=0)
    fields=raw[end+2:].split();require(len(fields)>=20)
    return {'pid':pid,'startTicks':fields[19],'netnsInode':(proc/'ns/net').stat().st_ino}
def container_observation(container_id,own_name,data):
    hexadecimal(container_id);docker=executable('docker');values=json_command([docker,'inspect',container_id]);require(len(values)==1)
    row=values[0];require(row['Id']==container_id and row['State']['Running'] is True,'BOOTSTRAP_BUSINESS_NETWORK_CONTAINER_UNREADY')
    lab=labels(row['Config'].get('Labels'));require(lab.get(SERVER_LABEL)==data['serverId'] and lab.get(JOB_LABEL)==data['jobId']
        and lab.get('com.docker.compose.project')==project(data) and lab.get('com.docker.compose.service') in ['app','postgres','redis']
        and row['HostConfig']['NetworkMode']==own_name and set(row['NetworkSettings']['Networks'])=={own_name},'BOOTSTRAP_BUSINESS_NETWORK_OWNERSHIP_CHANGED')
    pid=integer(row['State']['Pid'],2);before=namespace_identity(pid);ip=executable('ip');ns=executable('nsenter')
    links=json_command([ns,'--target',str(pid),'--net','--',ip,'-j','-d','link','show'])
    addresses=json_command([ns,'--target',str(pid),'--net','--',ip,'-j','-4','address','show'])
    require(before==namespace_identity(pid),'BOOTSTRAP_BUSINESS_NETWORK_PROCESS_CHANGED')
    # Docker inspect includes database passwords in Env: never persist/return it.
    return {'Id':container_id,'Labels':labels(row['Config'].get('Labels')),'Running':True,'process':before,
        'NetworkMode':row['HostConfig']['NetworkMode'],'Networks':row['NetworkSettings']['Networks'],
        'Ports':row['NetworkSettings'].get('Ports') or {},'PortBindings':row['HostConfig'].get('PortBindings') or {},'links':links,'addresses':addresses}
def owned_observations(data):
    request(data);networks=docker_networks();name=project(data)+'_default';own=[n for n in networks.values() if n['Name']==name];require(len(own)==1)
    require(own[0]['Id']==data['expectedNetworkId'] and own[0]['Labels'].get(SERVER_LABEL)==data['serverId']
        and own[0]['Labels'].get(JOB_LABEL)==data['jobId'],'BOOTSTRAP_BUSINESS_NETWORK_OWNERSHIP_CHANGED')
    require(len(own[0]['Containers'])==3,'BOOTSTRAP_BUSINESS_NETWORK_CONTAINER_SET_CHANGED')
    return networks,[container_observation(cid,name,data) for cid in own[0]['Containers']]
def observe_kernel_graph(data):
    first=network();networks,containers=owned_observations(data);last=network()
    require(digest(first)==digest(last) and networks==docker_networks(),'BOOTSTRAP_BUSINESS_NETWORK_CHANGED_DURING_OBSERVATION')
    require(all(c['process']==namespace_identity(c['process']['pid']) for c in containers),'BOOTSTRAP_BUSINESS_NETWORK_PROCESS_CHANGED')
    return {'network':last,'dockerNetworks':networks,'containers':containers}
def configuration(path,maximum=65536,private=False,allowed_uids=(0,)):
    path=Path(path)
    if not path.exists() and not path.is_symlink():return None
    resolved=path.resolve(strict=True)
    if path.is_symlink() and resolved==Path('/dev/null') and not private:return {'path':str(path),'masked':True}
    require(all(p.stat().st_uid in allowed_uids and p.stat().st_mode&0o022==0 for p in resolved.parents),'BOOTSTRAP_BUSINESS_NETWORK_UNSAFE_CONFIGURATION')
    info=resolved.stat();require(resolved.is_file() and info.st_uid in allowed_uids and info.st_mode&0o022==0 and info.st_size<=maximum
        and (not private or stat.S_IMODE(info.st_mode)==0o600),'BOOTSTRAP_BUSINESS_NETWORK_UNSAFE_CONFIGURATION')
    with resolved.open('rb') as stream:raw=stream.read(maximum+1)
    require(len(raw)<=maximum and info.st_ino==resolved.stat().st_ino and info.st_mtime_ns==resolved.stat().st_mtime_ns,'BOOTSTRAP_BUSINESS_NETWORK_CONFIGURATION_CHANGED')
    return {'path':str(path),'target':str(resolved),'symlink':os.readlink(path) if path.is_symlink() else None,
        'sha256':hashlib.sha256(raw).hexdigest(),'mode':stat.S_IMODE(info.st_mode)}
def systemd(unit,property):return run([executable('systemctl'),'show',unit,'--property='+property,'--value']).decode().strip()
def resolver_rows(raw):
    result={}
    for line in raw.decode().splitlines():
        name,separator,value=line.partition(':');require(separator,'BOOTSTRAP_BUSINESS_NETWORK_DNS_UNOBSERVABLE')
        values=value.split()
        if values:require(name not in result);result[name]=values
    return result
def dns_digest():
    active=systemd('systemd-resolved.service','ActiveState');uids=(0,)
    if active=='active':
        user=systemd('systemd-resolved.service','User');require(user in ['','root','systemd-resolve'],'BOOTSTRAP_BUSINESS_NETWORK_DNS_UNOBSERVABLE')
        if user=='systemd-resolve':uids=(0,pwd.getpwnam(user).pw_uid)
    paths=[Path('/etc/resolv.conf'),Path('/etc/systemd/resolved.conf')]
    for directory in ['/etc/systemd/resolved.conf.d','/run/systemd/resolved.conf.d','/usr/lib/systemd/resolved.conf.d']:
        folder=Path(directory)
        if folder.exists():require(folder.is_dir() and not folder.is_symlink());paths.extend(sorted(folder.glob('*.conf')))
    require(len(paths)<=64,'BOOTSTRAP_BUSINESS_NETWORK_DNS_CONFIGURATION_TOO_LARGE')
    files=[configuration(p,allowed_uids=uids if i==0 else (0,)) for i,p in enumerate(paths)];require(files[0] is not None and not files[0].get('masked'),'BOOTSTRAP_BUSINESS_NETWORK_DNS_UNOBSERVABLE')
    facts={'files':files,'resolvedActiveState':active}
    if active=='active':
        resolver=executable('resolvectl');facts['servers']=resolver_rows(run([resolver,'dns']));facts['domains']=resolver_rows(run([resolver,'domain']))
        for prop in ['FragmentPath','DropInPaths']:
            paths=shlex.split(systemd('systemd-resolved.service',prop));require(len(paths)<=32)
            facts[prop]=[configuration(Path(p)) for p in paths]
    else:
        # A local resolver other than the reviewed systemd service needs its
        # own configuration collector; unchanged stub bytes are insufficient.
        text=Path('/etc/resolv.conf').read_text()
        for line in text.splitlines():
            fields=line.split('#',1)[0].split()
            if fields and fields[0]=='nameserver':require(len(fields)==2 and not ipaddress.ip_address(fields[1].split('%',1)[0]).is_loopback,'BOOTSTRAP_BUSINESS_NETWORK_LOCAL_RESOLVER_UNSUPPORTED')
    return digest(facts)
def wireguard_digest():
    path=Path('/etc/wireguard/ewctrl0.conf');config=configuration(path,private=True)
    require(config is not None and systemd('wg-quick@ewctrl0.service','ActiveState')=='active'
        and systemd('wg-quick@ewctrl0.service','UnitFileState')=='enabled','BOOTSTRAP_BUSINESS_NETWORK_PRIVATE_LINK_UNREADY')
    wg=executable('wg');values={}
    for prop in ['public-key','listen-port','fwmark']:values[prop]=run([wg,'show','ewctrl0',prop]).decode().strip()
    peers={}
    for line in run([wg,'show','ewctrl0','allowed-ips']).decode().splitlines():
        cols=line.split();require(len(cols)>=2 and cols[0] not in peers);networks=[]
        for word in cols[1:]:
            for prefix in word.split(','):
                if prefix!='(none)':networks.append(str(ipaddress.ip_network(prefix,strict=False)))
        peers[cols[0]]=sorted(networks)
    return digest({'configuration':config,'runtime':values,'peers':peers})
def daemon_observation():
    binary=shutil.which('docker',path=ENV['PATH'])
    if binary is None:
        require(shutil.which('docker',path='/usr/local/sbin:/usr/local/bin:'+ENV['PATH']) is None
            and shutil.which('dockerd',path='/usr/local/sbin:/usr/local/bin:'+ENV['PATH']) is None
            and all(not p.exists() and not p.is_symlink() for p in map(Path,['/etc/docker','/var/lib/docker','/run/docker.sock','/run/docker.pid']))
            and systemd('docker.service','LoadState')=='not-found','BOOTSTRAP_BUSINESS_NETWORK_EXISTING_DOCKER_UNREVIEWED')
        return None,{}
    # Do not start an administrator's stopped daemon or switch Docker contexts.
    try:return run([executable('docker'),'info','--format','{{.ID}}']).decode().strip(),docker_networks()
    except Exception:raise Refusal('BOOTSTRAP_BUSINESS_NETWORK_DOCKER_UNAVAILABLE') from None
def snapshot(graph=None):
    daemon,nets=daemon_observation()
    if graph is not None:require(nets==graph['dockerNetworks'],'BOOTSTRAP_BUSINESS_NETWORK_CHANGED_DURING_OBSERVATION')
    value={'network':network() if graph is None else graph['network'],'daemonId':daemon,'dockerNetworks':nets,
        'dnsSha256':dns_digest(),'wireguardSha256':wireguard_digest(),'ipForward':int(Path('/proc/sys/net/ipv4/ip_forward').read_text())}
    return snapshot_shape(value)
def firewall_context():
    value=json_command([executable('nft'),'-j','-s','list','ruleset'])
    def stable(item):
        if isinstance(item,list):return [stable(v) for v in item if not isinstance(v,dict) or 'metainfo' not in v]
        if isinstance(item,dict):return {k:({a:b for a,b in v.items() if a not in ['packets','bytes']} if k=='counter' and isinstance(v,dict) else stable(v)) for k,v in item.items() if k!='handle'}
        return item
    result={'nft':stable(value)}
    for name in ['iptables-save','ip6tables-save']:
        if shutil.which(name,path=ENV['PATH']):
            lines=run([executable(name)]).decode().splitlines()
            result[name]=[re.sub(r'^(\S+\s+\S+)\s+\[\d+:\d+\]$',r'\1 [0:0]',line) for line in lines if line and not line.startswith('#')]
        else:result[name]=None
    return result
def ipv6_context():
    ip=executable('ip');addresses=json_command([ip,'-j','-6','address','show']);routes=json_command([ip,'-j','-6','route','show','table','all'])
    ipv6={'addresses':[{k:row[k] for k in ['ifindex','ifname','flags'] if k in row}|{'addresses':[{k:a[k] for k in ['local','prefixlen','scope','tentative','dadfailed','deprecated','dynamic','mngtmpaddr','noprefixroute'] if k in a} for a in row.get('addr_info',[])]} for row in addresses],
        'routes':[{k:v for k,v in row.items() if k not in ['expires','cacheinfo','used','lastuse']} for row in routes]}
    folders=list(Path('/proc/sys/net/ipv6/conf').iterdir());require(len(folders)<=514)
    ipv6['sysctls']={interface(p.name):{key:int((p/key).read_text()) for key in ['forwarding','accept_ra','disable_ipv6']} for p in folders}
    return ipv6
def capture_context():
    value={'snapshot':snapshot(),'ipv6':ipv6_context(),'firewall':firewall_context()};require(len(canonical(value))<=1048576,'BOOTSTRAP_BUSINESS_NETWORK_CONTEXT_TOO_LARGE');return value
def snapshot_shape(value):
    require(isinstance(value,dict) and set(value)=={'network','daemonId','dnsSha256','wireguardSha256','ipForward','dockerNetworks'})
    require(value['daemonId'] is None or isinstance(value['daemonId'],str) and re.fullmatch('[A-Za-z0-9:-]{1,128}',value['daemonId']))
    hexadecimal(value['dnsSha256']);require(value['wireguardSha256'] is None or hexadecimal(value['wireguardSha256']))
    integer(value['ipForward'],0,1);require(isinstance(value['dockerNetworks'],dict) and len(value['dockerNetworks'])<=128)
    for key,row in value['dockerNetworks'].items():hexadecimal(key);require(row['Id']==key)
    net=value['network'];require(set(net)=={'addresses','routes','links'} and isinstance(net['links'],dict))
    require(isinstance(net['addresses'],list) and isinstance(net['routes'],list) and len(net['links'])<=512 and len(net['routes'])<=4096)
    require(len({r['ifname'] for r in net['addresses']})==len(net['addresses']) and set(net['links'])=={r['ifname'] for r in net['addresses']})
    for row in net['routes']:require(isinstance(row,dict) and set(row)<=ROUTE_KEYS)
    require(len(canonical(value))<=1048576,'BOOTSTRAP_BUSINESS_NETWORK_OBSERVATION_TOO_LARGE');return value
def network_descriptor(row,default=False):
    hexadecimal(row['Id']);require(row['Driver']=='bridge' and row['Scope']=='local' and row['EnableIPv6'] is False
        and row['Internal'] is False and row['Ingress'] is False and row['ConfigOnly'] is False,'BOOTSTRAP_BUSINESS_NETWORK_DRIVER_CHANGED')
    ipam=row['IPAM'];require(isinstance(ipam,dict) and ipam.get('Driver')=='default' and not ipam.get('Options') and len(ipam.get('Config',[]))==1)
    config=ipam['Config'][0];require({'Subnet','Gateway'}<=set(config)<= {'Subnet','Gateway','IPRange'} and config.get('IPRange','')=='')
    subnet=ipaddress.IPv4Network(config['Subnet'],strict=True);gateway=ipaddress.IPv4Address(config['Gateway'])
    require(8<=subnet.prefixlen<=29 and gateway in subnet and gateway not in [subnet.network_address,subnet.broadcast_address]
        and any(subnet.subnet_of(ipaddress.IPv4Network(net)) for net in ['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16']),'BOOTSTRAP_BUSINESS_NETWORK_IPAM_CHANGED')
    options=row['Options'];require(isinstance(options,dict))
    if default:
        require(row['Name']=='bridge' and not row['Labels'] and not row['Containers'] and options.get('com.docker.network.bridge.default_bridge')=='true'
            and options.get('com.docker.network.bridge.name')=='docker0','BOOTSTRAP_BUSINESS_NETWORK_DEFAULT_BRIDGE_CHANGED')
        name='docker0'
    else:
        require(set(options)<= {'com.docker.network.bridge.name'},'BOOTSTRAP_BUSINESS_NETWORK_DRIVER_CHANGED');name=options.get('com.docker.network.bridge.name','br-'+row['Id'][:12])
    interface(name);return name,subnet,gateway
def route_kind(row,name,subnet,gateway):
    if row.get('dev')!=name or row.get('protocol')!='kernel' or row.get('prefsrc')!=str(gateway) or 'gateway' in row or 'metric' in row:return None
    if row.get('table','main')=='main' and row.get('type','unicast')=='unicast' and row.get('scope')=='link' and row.get('dst')==str(subnet):return 'connected'
    if row.get('table')=='local' and row.get('type')=='local' and row.get('scope')=='host' and row.get('dst')==str(gateway):return 'gateway'
    if row.get('table')=='local' and row.get('type')=='broadcast' and row.get('scope')=='link' and row.get('dst') in [str(subnet.network_address),str(subnet.broadcast_address)]:return 'broadcast:'+row['dst']
    return None
def verify(data,before,after,containers):
    request(data);snapshot_shape(before);snapshot_shape(after);require(isinstance(containers,list) and len(containers)==3 and len(canonical(containers))<=1048576)
    require(before['dnsSha256']==after['dnsSha256'] and before['wireguardSha256']==after['wireguardSha256'],'BOOTSTRAP_BUSINESS_NETWORK_EXISTING_CONFIGURATION_CHANGED')
    fresh=before['daemonId'] is None
    require(after['daemonId'] is not None and (not fresh and before['daemonId']==after['daemonId'] or fresh and data['allowNewDaemon'] and not before['dockerNetworks']),
        'BOOTSTRAP_BUSINESS_NETWORK_DAEMON_CHANGED')
    require(before['ipForward']==after['ipForward'] or fresh and data['allowIpForwardEnable'] and before['ipForward']==0 and after['ipForward']==1,
        'BOOTSTRAP_BUSINESS_NETWORK_FORWARDING_CHANGED')
    old=before['dockerNetworks'];now=after['dockerNetworks'];require(all(now.get(k)==v for k,v in old.items()),'BOOTSTRAP_BUSINESS_NETWORK_FOREIGN_NETWORK_CHANGED')
    added={k:v for k,v in now.items() if k not in old};wanted=project(data)+'_default';own=[n for n in added.values() if n['Name']==wanted]
    require(len(own)==1,'BOOTSTRAP_BUSINESS_NETWORK_NOT_NEW');own=own[0]
    require(own['Id']==data['expectedNetworkId'] and own['Labels'].get(SERVER_LABEL)==data['serverId'] and own['Labels'].get(JOB_LABEL)==data['jobId']
        and own['Labels'].get('com.docker.compose.project')==project(data) and own['Labels'].get('com.docker.compose.network')=='default',
        'BOOTSTRAP_BUSINESS_NETWORK_OWNERSHIP_CHANGED')
    bridges=[network_descriptor(own)];extra={k:v for k,v in added.items() if k!=own['Id']}
    if fresh:
        builtins={n['Name']:n for n in extra.values()};require(set(builtins)=={'bridge','host','none'} and len(extra)==3,'BOOTSTRAP_BUSINESS_NETWORK_UNEXPECTED_NETWORK')
        for name,driver in [('host','host'),('none','null')]:
            n=builtins[name];require(n['Driver']==driver and n['Scope']=='local' and not n['Labels'] and not n['Containers'] and not n['Options'],'BOOTSTRAP_BUSINESS_NETWORK_UNEXPECTED_NETWORK')
        bridges.append(network_descriptor(builtins['bridge'],True))
    else:require(not extra,'BOOTSTRAP_BUSINESS_NETWORK_UNEXPECTED_NETWORK')
    bn=before['network'];an=after['network'];old_addresses={r['ifname']:r for r in bn['addresses']};new_addresses={r['ifname']:r for r in an['addresses']}
    require(all(new_addresses.get(k)==v for k,v in old_addresses.items()) and all(an['links'].get(k)==v for k,v in bn['links'].items()),'BOOTSTRAP_BUSINESS_NETWORK_EXISTING_INTERFACE_CHANGED')
    allowed=set();subnets=[]
    for name,subnet,gateway in bridges:
        require(name not in bn['links'] and name in an['links'] and an['links'][name]['kind']=='bridge','BOOTSTRAP_BUSINESS_NETWORK_BRIDGE_CHANGED')
        require(all(not subnet.overlaps(other) for other in subnets),'BOOTSTRAP_BUSINESS_NETWORK_IPAM_CHANGED');subnets.append(subnet)
        for row in bn['routes']:
            dst=row.get('dst','default')
            if dst!='default':require(not subnet.overlaps(ipaddress.IPv4Network(dst,strict=False)),'BOOTSTRAP_BUSINESS_NETWORK_IPAM_COLLISION')
        require(new_addresses[name]['addresses']==[{'local':str(gateway),'prefixlen':subnet.prefixlen,'scope':'global'}]
            and 'UP' in new_addresses[name]['flags'],'BOOTSTRAP_BUSINESS_NETWORK_BRIDGE_CHANGED');allowed.add(name)
    name,subnet,gateway=bridges[0];bridge_index=integer(an['links'][name]['ifindex']);seen=set();host_indexes=set();namespace_ids=set();services=set()
    require(set(own['Containers'])=={c['Id'] for c in containers},'BOOTSTRAP_BUSINESS_NETWORK_CONTAINER_SET_CHANGED')
    for c in containers:
        hexadecimal(c['Id']);lab=c['Labels'];service=lab.get('com.docker.compose.service');require(service in ['app','postgres','redis'] and service not in services,'BOOTSTRAP_BUSINESS_NETWORK_CONTAINER_SET_CHANGED');services.add(service)
        require(lab.get(SERVER_LABEL)==data['serverId'] and lab.get(JOB_LABEL)==data['jobId'] and lab.get('com.docker.compose.project')==project(data)
            and lab.get('com.docker.compose.project.config_files')=='/opt/eatwhat-sites/'+data['serverId']+'/deploy/compose.json'
            and c['Running'] is True and c['NetworkMode']==own['Name'] and set(c['Networks'])=={own['Name']},'BOOTSTRAP_BUSINESS_NETWORK_OWNERSHIP_CHANGED')
        proc=c['process'];integer(proc['pid'],2);integer(proc['netnsInode'],1,18446744073709551615);require(isinstance(proc['startTicks'],str) and proc['startTicks'].isdigit())
        require(proc['netnsInode'] not in namespace_ids,'BOOTSTRAP_BUSINESS_NETWORK_NAMESPACE_CHANGED');namespace_ids.add(proc['netnsInode'])
        endpoint=c['Networks'][own['Name']];peer=own['Containers'][c['Id']];hexadecimal(endpoint['EndpointID'])
        ip=ipaddress.IPv4Address(endpoint['IPAddress']);require(ip in subnet and ip!=gateway and ip not in [subnet.network_address,subnet.broadcast_address])
        require(endpoint['NetworkID']==own['Id'] and endpoint['Gateway']==str(gateway) and endpoint['IPPrefixLen']==subnet.prefixlen
            and not endpoint.get('GlobalIPv6Address') and endpoint['EndpointID']==peer['EndpointID'] and endpoint['MacAddress']==peer['MacAddress']
            and peer['IPv4Address']==str(ip)+'/'+str(subnet.prefixlen) and not peer['IPv6Address'],'BOOTSTRAP_BUSINESS_NETWORK_ENDPOINT_CHANGED')
        links=c['links'];require(isinstance(links,list) and len(links)==2 and {r['ifname'] for r in links}=={'lo','eth0'},'BOOTSTRAP_BUSINESS_NETWORK_NAMESPACE_CHANGED')
        eth=[r for r in links if r['ifname']=='eth0'][0];integer(eth['ifindex']);host=integer(eth['link_index'])
        require(host not in host_indexes and eth.get('address')==endpoint['MacAddress'] and eth.get('linkinfo',{}).get('info_kind')=='veth','BOOTSTRAP_BUSINESS_NETWORK_VETH_CHANGED');host_indexes.add(host)
        candidates=[(n,r) for n,r in an['links'].items() if r['ifindex']==host];require(len(candidates)==1,'BOOTSTRAP_BUSINESS_NETWORK_VETH_CHANGED');host_name,h=candidates[0]
        require(host_name not in bn['links'] and h['kind']=='veth' and h['master']==bridge_index and h['peerIfindex']==eth['ifindex']
            and not new_addresses[host_name]['addresses'] and 'UP' in new_addresses[host_name]['flags'],'BOOTSTRAP_BUSINESS_NETWORK_VETH_CHANGED')
        addresses=c['addresses'];require({r['ifname'] for r in addresses}=={'lo','eth0'} and len(addresses)==2)
        addr=[r for r in addresses if r['ifname']=='eth0'][0]
        require(addr['ifindex']==eth['ifindex'] and 'UP' in addr.get('flags',[]) and [{k:v[k] for k in ['local','prefixlen','scope']} for v in addr.get('addr_info',[])]==[{'local':str(ip),'prefixlen':subnet.prefixlen,'scope':'global'}],
            'BOOTSTRAP_BUSINESS_NETWORK_ENDPOINT_CHANGED')
        require(str(ip) not in seen,'BOOTSTRAP_BUSINESS_NETWORK_ENDPOINT_CHANGED');seen.add(str(ip));allowed.add(host_name)
    require(set(an['links'])-set(bn['links'])==allowed and set(new_addresses)-set(old_addresses)==allowed,'BOOTSTRAP_BUSINESS_NETWORK_UNEXPECTED_INTERFACE')
    original=Counter(canonical(r) for r in bn['routes']);current=Counter(canonical(r) for r in an['routes'])
    require(all(current[k]>=n for k,n in original.items()),'BOOTSTRAP_BUSINESS_NETWORK_EXISTING_ROUTE_CHANGED');added_routes=current-original;counts=Counter()
    for raw,count in added_routes.items():
        row=json.loads(raw);matches=[(bridge,route_kind(row,bridge,net,gate)) for bridge,net,gate in bridges];matches=[m for m in matches if m[1] is not None]
        require(count==1 and len(matches)==1,'BOOTSTRAP_BUSINESS_NETWORK_UNEXPECTED_ROUTE');counts[matches[0]]+=1
    for bridge,net,gate in bridges:require(counts[(bridge,'connected')]==1 and counts[(bridge,'gateway')]==1,'BOOTSTRAP_BUSINESS_NETWORK_ROUTE_MISSING')
    return {'protocol':PROTOCOL,'state':'KERNEL_IPV4_DELTA_VERIFIED','serverId':data['serverId'],'jobId':data['jobId'],'approvalDigest':data['approvalDigest'],
        'inputBinding':data['inputBinding'],'baselineSha256':digest(before),'observationSha256':digest({'after':after,'containers':containers}),
        'ownedNetworkId':own['Id'],'addedInterfaces':sorted(allowed),'kernelIpv4DeltaVerified':True,'ipv4DefaultRoutePreserved':True,'dnsPreserved':True,
        'ipv6RevalidationRequired':True,'privateLinkRevalidationRequired':True,'firewallRevalidationRequired':True,'businessHealthVerified':False,'joinAuthorized':False}
