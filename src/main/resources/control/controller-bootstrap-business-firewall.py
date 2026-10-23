"""Exact iptables-nft Docker bridge effects, independently matched to nft JSON.

Descriptor must come from an authenticated kernel/namespace ownership proof,
original creation ID and approved port/effects. No chain/name prefix is ignored.
Unknown backends or rule shapes refuse rather than broadening the allowed delta.
No firewall/network mutation and no JOIN authority is provided by this module.
"""
from collections import Counter
import hashlib
import ipaddress
import json
import re
import shlex

PROTOCOL='eatwhat-business-firewall-v1'
class Refusal(Exception):
    def __init__(self,code='BOOTSTRAP_BUSINESS_FIREWALL_UNCONFIRMED'):self.code=code;super().__init__(code)
def require(ok,code='BOOTSTRAP_BUSINESS_FIREWALL_UNCONFIRMED'):
    if not ok:raise Refusal(code)
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':')).encode()
def digest(value):return hashlib.sha256(canonical(value)).hexdigest()
def parse(lines):
    if lines is None:return {}
    require(isinstance(lines,list) and len(lines)<=8192);tables={};table=None
    for line in lines:
        require(isinstance(line,str) and len(line)<=8192)
        if line.startswith('*'):
            require(table is None and re.fullmatch(r'\*[a-z][a-z0-9_-]{0,31}',line) and line[1:] not in tables)
            table=line[1:];tables[table]={}
        elif line=='COMMIT':require(table is not None);table=None
        elif line.startswith(':'):
            match=re.fullmatch(r':([A-Za-z0-9_-]{1,64}) (ACCEPT|DROP|-) \[0:0\]',line)
            require(table is not None and match and match[1] not in tables[table]);tables[table][match[1]]={'policy':match[2],'rules':[]}
        else:
            tokens=shlex.split(line);require(table is not None and len(tokens)>=4 and tokens[0]=='-A' and tokens[1] in tables[table])
            tables[table][tokens[1]]['rules'].append(tokens[2:])
    require(table is None);return tables
def key(value):return canonical(value).decode()
def sequence_delta(before,after):
    # Preserve every original rule and its relative ordering. New entries are
    # subsequently required to equal the complete approved concrete delta.
    old=iter(before);next_old=next(old,None);added=[]
    for item in after:
        if next_old is not None and item==next_old:next_old=next(old,None)
        else:added.append(item)
    require(next_old is None,'BOOTSTRAP_BUSINESS_FIREWALL_ORIGINAL_RULE_CHANGED');return added
def match(left,right,op='=='):return {'match':{'op':op,'left':left,'right':right}}
def meta(name,value,op='=='):return match({'meta':{'key':name}},value,op)
def address(family,field,value,op='=='):
    net=ipaddress.ip_network(value,strict=False)
    right=str(net.network_address) if net.prefixlen==net.max_prefixlen else {'prefix':{'addr':str(net.network_address),'len':net.prefixlen}}
    return match({'payload':{'protocol':family,'field':field}},right,op)
def tcp(port):return match({'payload':{'protocol':'tcp','field':'dport'}},port)
def xt(kind,name):return {'xt':{'type':kind,'name':name}}
def concrete_rules(d):
    require(set(d)=={'bridge','subnet','containerIps','appIp','httpPort','freshDaemon','defaultSubnet','allowForwardPolicyDrop'})
    require(re.fullmatch(r'[A-Za-z0-9_.:-]{1,15}',d['bridge']) and type(d['freshDaemon']) is bool and type(d['allowForwardPolicyDrop']) is bool)
    net=ipaddress.IPv4Network(d['subnet'],strict=True);ips=d['containerIps'];require(isinstance(ips,list) and len(ips)==3 and len(set(ips))==3 and d['appIp'] in ips)
    require(all(ipaddress.IPv4Address(ip) in net for ip in ips) and type(d['httpPort']) is int and 1024<=d['httpPort']<=65535)
    require(not d['freshDaemon'] or d['allowForwardPolicyDrop'],'BOOTSTRAP_BUSINESS_FIREWALL_POLICY_SCOPE_REQUIRED')
    rules={};chains={}
    def add(family,table,chain,text,expr):
        tokens=shlex.split(text);entry={'tokens':tokens,'expr':expr};rules.setdefault((family,table,chain),[]).append(entry)
    def declarations(family,table,names):
        for name,policy in names.items():chains[(family,table,name)]=policy
    if d['freshDaemon']:
        for family in ['ip','ip6']:
            declarations(family,'filter',{'INPUT':'ACCEPT','FORWARD':'DROP' if family=='ip' else 'ACCEPT','OUTPUT':'ACCEPT',
                **{n:'-' for n in ['DOCKER','DOCKER-BRIDGE','DOCKER-CT','DOCKER-FORWARD','DOCKER-INTERNAL','DOCKER-USER']}})
            declarations(family,'nat',{'PREROUTING':'ACCEPT','INPUT':'ACCEPT','OUTPUT':'ACCEPT','POSTROUTING':'ACCEPT','DOCKER':'-'})
            for target in ['DOCKER-USER','DOCKER-FORWARD']:add(family,'filter','FORWARD','-j '+target,[{'counter':None},{'jump':{'target':target}}])
            for target in ['DOCKER-CT','DOCKER-INTERNAL','DOCKER-BRIDGE']:add(family,'filter','DOCKER-FORWARD','-j '+target,[{'counter':None},{'jump':{'target':target}}])
            add(family,'nat','PREROUTING','-m addrtype --dst-type LOCAL -j DOCKER',[xt('match','addrtype'),{'counter':None},{'jump':{'target':'DOCKER'}}])
            loop='127.0.0.0/8' if family=='ip' else '::1/128'
            add(family,'nat','OUTPUT','! -d '+loop+' -m addrtype --dst-type LOCAL -j DOCKER',[address(family,'daddr',loop,'!='),xt('match','addrtype'),{'counter':None},{'jump':{'target':'DOCKER'}}])
    declarations('ip','raw',{'PREROUTING':'ACCEPT','OUTPUT':'ACCEPT'})
    bridges=[(d['bridge'],str(net))]
    if d['freshDaemon']:
        default=ipaddress.IPv4Network(d['defaultSubnet'],strict=True);require(not default.overlaps(net));bridges.append(('docker0',str(default)))
    else:require(d['defaultSubnet'] is None)
    app=d['appIp'];bridge=d['bridge'];port=d['httpPort']
    add('ip','filter','DOCKER',f'-d {app}/32 ! -i {bridge} -o {bridge} -p tcp -m tcp --dport 8080 -j ACCEPT',
        [address('ip','daddr',app),meta('iifname',bridge,'!='),meta('oifname',bridge),tcp(8080),{'counter':None},{'accept':None}])
    for name,subnet in bridges:
        add('ip','filter','DOCKER',f'! -i {name} -o {name} -j DROP',[meta('iifname',name,'!='),meta('oifname',name),{'counter':None},{'drop':None}])
        add('ip','filter','DOCKER-BRIDGE',f'-o {name} -j DOCKER',[meta('oifname',name),{'counter':None},{'jump':{'target':'DOCKER'}}])
        add('ip','filter','DOCKER-CT',f'-o {name} -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT',[meta('oifname',name),xt('match','conntrack'),{'counter':None},{'accept':None}])
        add('ip','filter','DOCKER-FORWARD',f'-i {name} -j ACCEPT',[meta('iifname',name),{'counter':None},{'accept':None}])
        add('ip','nat','POSTROUTING',f'-s {subnet} ! -o {name} -j MASQUERADE',[address('ip','saddr',subnet),meta('oifname',name,'!='),{'counter':None},xt('target','MASQUERADE')])
    for ip in ips:add('ip','raw','PREROUTING',f'-d {ip}/32 ! -i {bridge} -j DROP',[address('ip','daddr',ip),meta('iifname',bridge,'!='),{'counter':None},{'drop':None}])
    add('ip','raw','PREROUTING',f'-d 127.0.0.1/32 ! -i lo -p tcp -m tcp --dport {port} -j DROP',
        [address('ip','daddr','127.0.0.1'),meta('iifname','lo','!='),tcp(port),{'counter':None},{'drop':None}])
    add('ip','nat','DOCKER',f'-d 127.0.0.1/32 ! -i {bridge} -p tcp -m tcp --dport {port} -j DNAT --to-destination {app}:8080',
        [address('ip','daddr','127.0.0.1'),meta('iifname',bridge,'!='),tcp(port),{'counter':None},xt('target','DNAT')])
    return rules,chains
def nft_parts(value):
    require(isinstance(value,dict) and set(value)=={'nftables'} and isinstance(value['nftables'],list));tables={};chains={};rules={};other=[]
    for entry in value['nftables']:
        require(isinstance(entry,dict) and len(entry)==1)
        kind,row=next(iter(entry.items()))
        if kind=='metainfo':continue
        if kind=='table':
            k=(row['family'],row['name']);require(k not in tables);tables[k]=row
        elif kind=='chain':
            k=(row['family'],row['table'],row['name']);require(k not in chains);chains[k]=row
        elif kind=='rule':rules.setdefault((row['family'],row['table'],row['chain']),[]).append(row)
        else:other.append(entry)
    return tables,chains,rules,other
def nft_chain(k,policy):
    family,table,name=k;row={'family':family,'table':table,'name':name}
    if policy!='-':
        hooks={'PREROUTING':'prerouting','INPUT':'input','FORWARD':'forward','OUTPUT':'output','POSTROUTING':'postrouting'};require(name in hooks)
        priority=0 if table=='filter' else -300 if table=='raw' else 100 if name=='POSTROUTING' else -100
        row.update(type='nat' if table=='nat' else 'filter',hook=hooks[name],prio=priority,policy=policy.lower())
    return row
def verify_firewall(descriptor,before,after):
    require(set(before)==set(after)=={'nft','iptables-save','ip6tables-save'})
    expected,allowed_chains=concrete_rules(descriptor);added={};new_chains={};changes={}
    for family,name in [('ip','iptables-save'),('ip6','ip6tables-save')]:
        old=parse(before[name]);now=parse(after[name]);require(now,'BOOTSTRAP_BUSINESS_FIREWALL_BACKEND_UNSUPPORTED')
        require(set(old)<=set(now),'BOOTSTRAP_BUSINESS_FIREWALL_ORIGINAL_TABLE_CHANGED')
        for table,chains in now.items():
            previous=old.get(table,{})
            require(set(previous)<=set(chains),'BOOTSTRAP_BUSINESS_FIREWALL_ORIGINAL_CHAIN_CHANGED')
            for chain,row in chains.items():
                k=(family,table,chain);prior=previous.get(chain)
                if prior is None:
                    require(allowed_chains.get(k)==row['policy'],'BOOTSTRAP_BUSINESS_FIREWALL_UNEXPECTED_CHAIN');new_chains[k]=row['policy']
                elif prior['policy']!=row['policy']:
                    require(descriptor['freshDaemon'] and descriptor['allowForwardPolicyDrop'] and k==('ip','filter','FORWARD') and prior['policy']=='ACCEPT' and row['policy']=='DROP',
                        'BOOTSTRAP_BUSINESS_FIREWALL_ORIGINAL_POLICY_CHANGED');changes[k]='DROP'
                delta=sequence_delta(prior['rules'] if prior else [],row['rules']);want=expected.get(k,[])
                require(Counter(map(key,delta))==Counter(key(v['tokens']) for v in want),'BOOTSTRAP_BUSINESS_FIREWALL_UNEXPECTED_RULE')
                if delta:added[k]=delta
        require(all(k in added for k in expected if k[0]==family),'BOOTSTRAP_BUSINESS_FIREWALL_EXPECTED_RULE_MISSING')
    bt,bc,br,bo=nft_parts(before['nft']);at,ac,ar,ao=nft_parts(after['nft'])
    require(all(k[:2] in at for k in ac) and all(k in ac for k in ar),'BOOTSTRAP_BUSINESS_FIREWALL_NATIVE_STRUCTURE_INVALID')
    require(bo==ao and all(at.get(k)==v for k,v in bt.items()),'BOOTSTRAP_BUSINESS_FIREWALL_NATIVE_OBJECT_CHANGED')
    for k,value in bc.items():
        wanted={**value,'policy':'drop'} if k in changes else value
        require(ac.get(k)==wanted,'BOOTSTRAP_BUSINESS_FIREWALL_NATIVE_CHAIN_CHANGED')
    for k,value in at.items():
        if k not in bt:require(value=={'family':k[0],'name':k[1]} and any(x[:2]==k for x in added),'BOOTSTRAP_BUSINESS_FIREWALL_UNEXPECTED_NATIVE_TABLE')
    # Empty iptables built-ins may be materialized lazily by the nft backend.
    # Their complete fixed hook/priority/policy is still checked if present.
    for k,value in ac.items():
        if k not in bc:
            policy=new_chains.get(k)
            if policy is None:
                name='iptables-save' if k[0]=='ip' else 'ip6tables-save' if k[0]=='ip6' else None
                row=parse(after[name]).get(k[1],{}).get(k[2]) if name else None
                require(row is not None and any(x[:2]==k[:2] for x in added));policy=row['policy']
            require(value==nft_chain(k,policy),'BOOTSTRAP_BUSINESS_FIREWALL_UNEXPECTED_NATIVE_CHAIN')
    require(set(br)<=set(ar),'BOOTSTRAP_BUSINESS_FIREWALL_ORIGINAL_NATIVE_RULE_CHANGED')
    for k,rows in ar.items():
        delta=sequence_delta(br.get(k,[]),rows);want=[{'family':k[0],'table':k[1],'chain':k[2],'expr':v['expr']} for v in expected.get(k,[])]
        require(Counter(map(key,delta))==Counter(map(key,want)),'BOOTSTRAP_BUSINESS_FIREWALL_NATIVE_RULE_MISMATCH')
    require(all(k in ar for k in expected),'BOOTSTRAP_BUSINESS_FIREWALL_NATIVE_RULE_MISSING')
    return {'protocol':PROTOCOL,'state':'FIREWALL_DELTA_VERIFIED','baselineSha256':digest(before),'observationSha256':digest(after),
        'firewallDeltaVerified':True,'originalRulesPreserved':True,'newRuleCount':sum(map(len,added.values())),
        'dockerForwardPolicyDropApproved':descriptor['freshDaemon'] and descriptor['allowForwardPolicyDrop'],'joinAuthorized':False}
def verify_ipv6(allowed_interfaces,current_links,before,after):
    require(set(before)==set(after)=={'addresses','routes','sysctls'},'BOOTSTRAP_BUSINESS_IPV6_BASELINE_INCOMPLETE')
    allowed=set(allowed_interfaces);require(allowed and allowed<=set(current_links))
    old={r['ifname']:r for r in before['addresses']};now={r['ifname']:r for r in after['addresses']}
    require(len(old)==len(before['addresses']) and len(now)==len(after['addresses']) and all(now.get(k)==v for k,v in old.items()),'BOOTSTRAP_BUSINESS_IPV6_EXISTING_ADDRESS_CHANGED')
    added={k:v for k,v in now.items() if k not in old};require(set(added)<=allowed,'BOOTSTRAP_BUSINESS_IPV6_FOREIGN_INTERFACE')
    for name,row in added.items():
        require(row['ifindex']==current_links[name]['ifindex'] and sorted(row['flags'])==current_links[name]['flags'],'BOOTSTRAP_BUSINESS_IPV6_INTERFACE_CHANGED')
        require(all(set(a)<={'local','prefixlen','scope','tentative','dadfailed','deprecated','dynamic','mngtmpaddr','noprefixroute'}
            and ipaddress.IPv6Address(a['local']) in ipaddress.IPv6Network('fe80::/64') and a['prefixlen']==64 and a['scope']=='link'
            and not any(a.get(k,False) for k in ['tentative','dadfailed','deprecated','dynamic','mngtmpaddr']) for a in row['addresses']),
            'BOOTSTRAP_BUSINESS_IPV6_UNEXPECTED_ADDRESS')
    original=Counter(map(key,before['routes']));current=Counter(map(key,after['routes']))
    require(all(current[k]>=count for k,count in original.items()),'BOOTSTRAP_BUSINESS_IPV6_ORIGINAL_ROUTE_CHANGED')
    for encoded,count in (current-original).items():
        row=json.loads(encoded);name=row.get('dev');require(count==1 and name in allowed,'BOOTSTRAP_BUSINESS_IPV6_FOREIGN_ROUTE')
        base={'dev':name,'protocol':'kernel','metric':256,'flags':[],'pref':'medium'}
        possibilities=[{**base,'dst':'fe80::/64'},{**base,'dst':'ff00::/8','table':'local','type':'multicast'}]
        possibilities += [{**base,'dst':a['local'],'metric':0,'table':'local','type':'local'} for a in now.get(name,{}).get('addresses',[])]
        require(row in possibilities,'BOOTSTRAP_BUSINESS_IPV6_UNEXPECTED_ROUTE')
    bs=before['sysctls'];ns=after['sysctls'];require({'all','default'}<=set(bs) and all(ns.get(k)==v for k,v in bs.items()),'BOOTSTRAP_BUSINESS_IPV6_ORIGINAL_SYSCTL_CHANGED')
    require(set(ns)-set(bs)<=allowed,'BOOTSTRAP_BUSINESS_IPV6_FOREIGN_SYSCTL')
    for name in set(ns)-set(bs):
        values=ns[name];require(set(values)=={'forwarding','accept_ra','disable_ipv6'} and values['forwarding']==bs['default']['forwarding']
            and values['accept_ra'] in [0,bs['default']['accept_ra']] and values['disable_ipv6'] in [bs['default']['disable_ipv6'],1],
            'BOOTSTRAP_BUSINESS_IPV6_UNEXPECTED_SYSCTL')
    return {'protocol':'eatwhat-business-ipv6-v1','state':'IPV6_DELTA_VERIFIED','baselineSha256':digest(before),'observationSha256':digest(after),
        'ipv6DeltaVerified':True,'originalAddressesAndRoutesPreserved':True,'originalSysctlsPreserved':True,'joinAuthorized':False}
