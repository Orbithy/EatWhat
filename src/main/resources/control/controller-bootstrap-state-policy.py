"""NFS state policy and bounded read-only NFSv4 probes. No filesystem mutation.

The RPC probe uses authenticated private-link source addresses and privileged
client ports. It implements only ACCESS and READ of the two identity files;
no OPEN/WRITE/SETATTR or other mutating RPC can be requested by an input body.
"""
import errno
import hashlib
import ipaddress
import json
import secrets
import socket
import struct
import time
import re
import uuid


class Refusal(Exception):
    def __init__(self, code):self.code=code;super().__init__(code)


class NfsRefusal(Refusal):
    def __init__(self,status):self.status=status;super().__init__('BOOTSTRAP_STATE_NFS_OPERATION_REFUSED')


def require(value, code='BOOTSTRAP_STATE_POLICY_INVALID'):
    if not value:raise Refusal(code)


def address(value):
    try:
        ip=ipaddress.IPv4Address(value)
        require(str(ip)==value and any(ip in ipaddress.ip_network(n) for n in ['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16']))
        return value
    except (ValueError,TypeError):raise Refusal('BOOTSTRAP_STATE_PRIVATE_ADDRESS_REQUIRED')


def domain(value):
    require(isinstance(value,str) and len(value)<=253 and '..' not in value
            and re.fullmatch(r'[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?',value),'BOOTSTRAP_STATE_IDMAP_DOMAIN_INVALID')
    return value


def ini_active(text):
    result={};section=None
    for line in text.splitlines():
        item=line.split('#',1)[0].split(';',1)[0].strip()
        if not item:continue
        if item.startswith('[') and item.endswith(']'):
            section=item[1:-1].strip().lower();require(section);continue
        require(section and '=' in item)
        key,value=[x.strip() for x in item.split('=',1)];key=key.lower()
        require(key and key not in result.setdefault(section,{}),'BOOTSTRAP_STATE_DUPLICATE_CONFIG')
        result[section][key]=value
    return {k:v for k,v in result.items() if v}


def idmap_content(original, expected_domain):
    domain(expected_domain)
    active=ini_active(original)
    present=active.get('general',{}).get('domain')
    require(present in [None,expected_domain],'BOOTSTRAP_STATE_TARGET_IDMAP_CONFIGURED')
    lines=[];section='';general=0
    for line in original.splitlines():
        item=line.split('#',1)[0].strip()
        if item.startswith('[') and item.endswith(']'):
            section=item[1:-1].strip().lower()
            if section=='general':
                general+=1;require(general==1);lines.extend([line,'Domain = '+expected_domain]);continue
        if section=='general' and '=' in item and item.split('=',1)[0].strip().lower()=='domain':continue
        lines.append(line)
    require(general==1,'BOOTSTRAP_STATE_IDMAP_GENERAL_REQUIRED')
    return ('\n'.join(lines)+'\n').encode()


def export_options(uid,gid):
    require(type(uid) is int and type(gid) is int and 100<=uid<60000 and 1<=gid<60000)
    return ['rw','sync','no_subtree_check','all_squash','anonuid='+str(uid),'anongid='+str(gid),'fsid=0','sec=sys']


def parse_exports(text, root, uid, gid, live=False):
    expected=set(export_options(uid,gid));clients={}
    benign={'wdelay','hide','nocrossmnt','secure','root_squash','secure_locks','acl'}
    for line in text.splitlines():
        line=line.split('#',1)[0].strip()
        if not line:continue
        fields=line.split();require(len(fields)>=2 and fields[0]==root,'BOOTSTRAP_STATE_FOREIGN_EXPORT')
        for item in fields[1:]:
            match=re.fullmatch(r'([0-9.]+)(?:/32)?\(([^()]*)\)',item)
            require(match is not None,'BOOTSTRAP_STATE_EXPORT_CLIENT_INVALID')
            client=address(match[1]);options=set(match[2].split(','))
            require(client not in clients,'BOOTSTRAP_STATE_DUPLICATE_EXPORT')
            require(expected<=options and options<=expected|benign if live else options in [expected,expected|{'secure'}],
                    'BOOTSTRAP_STATE_EXPORT_OPTIONS_UNSAFE')
            clients[client]=sorted(options)
    return clients


def export_content(root,clients,uid,gid):
    require(clients and len(clients)<=16)
    return (root+' '+ ' '.join(address(ip)+'('+','.join(export_options(uid,gid))+')' for ip in sorted(clients))+'\n').encode()


def firewall_content(clients):
    require(clients and len(clients)<=16)
    ips=', '.join(address(ip) for ip in sorted(clients))
    return ('''add table inet eatwhat_control_state
flush table inet eatwhat_control_state
add chain inet eatwhat_control_state ingress { type filter hook input priority -10; policy accept; }
add rule inet eatwhat_control_state ingress tcp dport 2049 iifname != "ewctrl0" drop
add rule inet eatwhat_control_state ingress tcp dport 2049 meta nfproto ipv6 drop
add rule inet eatwhat_control_state ingress tcp dport 2049 ip saddr != { '''+ips+''' } drop
add rule inet eatwhat_control_state ingress tcp dport { 111, 20048 } iifname != "lo" drop
add rule inet eatwhat_control_state ingress udp dport { 111, 2049, 20048 } iifname != "lo" drop
''').encode()


def legacy_firewall_content(client):
    text=firewall_content([client]).decode()
    return text.replace('ip saddr != { '+client+' }','ip saddr != '+client).replace(' iifname != "lo"','').encode()


def client_firewall_content(source):
    source=address(source)
    return ('''add table inet eatwhat_control_state_client
flush table inet eatwhat_control_state_client
add chain inet eatwhat_control_state_client output { type filter hook output priority -10; policy accept; }
add chain inet eatwhat_control_state_client forward { type filter hook forward priority -10; policy accept; }
add rule inet eatwhat_control_state_client output ip daddr '''+source+''' tcp dport 2049 meta skuid != 0 drop
add rule inet eatwhat_control_state_client forward ip daddr '''+source+''' tcp dport 2049 drop
''').encode()


def nft_normalize(value):
    if isinstance(value,list):return [nft_normalize(x) for x in value]
    if isinstance(value,dict):return {k:nft_normalize(v) for k,v in value.items() if k not in ['handle','index','packets','bytes','metainfo']}
    return value


def nft_guard_valid(document,clients,legacy=False):
    """Recognize only the exact five-rule private NFS guard, not arbitrary nft."""
    objects=[nft_normalize(x) for x in document.get('nftables',[]) if 'metainfo' not in x]
    require(len(objects)==7,'BOOTSTRAP_STATE_FIREWALL_UNRECOGNIZED')
    table=objects[0].get('table',{});require(table=={'family':'inet','name':'eatwhat_control_state'})
    chain=objects[1].get('chain',{})
    require(chain=={'family':'inet','table':'eatwhat_control_state','name':'ingress','type':'filter','hook':'input','prio':-10,'policy':'accept'},
            'BOOTSTRAP_STATE_FIREWALL_UNRECOGNIZED')
    def match(left,op,right):return {'match':{'op':op,'left':left,'right':right}}
    def dport(protocol,right):return match({'payload':{'protocol':protocol,'field':'dport'}},'==',right)
    expected=[
        [dport('tcp',2049),match({'meta':{'key':'iifname'}},'!=','ewctrl0'),{'drop':None}],
        [dport('tcp',2049),match({'meta':{'key':'nfproto'}},'==','ipv6'),{'drop':None}],
        [dport('tcp',2049),match({'payload':{'protocol':'ip','field':'saddr'}},'!=',{'set':sorted(clients)}),{'drop':None}],
        [dport('tcp',{'set':[111,20048]})]+([] if legacy else [match({'meta':{'key':'iifname'}},'!=','lo')])+[{'drop':None}],
        [dport('udp',{'set':[111,2049,20048]})]+([] if legacy else [match({'meta':{'key':'iifname'}},'!=','lo')])+[{'drop':None}]]
    for obj,expression in zip(objects[2:],expected):
        rule=obj.get('rule',{});require(set(rule)=={'family','table','chain','expr'}
            and rule['family']=='inet' and rule['table']=='eatwhat_control_state' and rule['chain']=='ingress')
        actual=rule['expr']
        # nft may fold a singleton literal address set into a scalar.
        if len(clients)==1 and len(actual)==3 and actual[1].get('match',{}).get('right')==clients[0]:
            actual=json.loads(json.dumps(actual));actual[1]['match']['right']={'set':list(clients)}
        require(actual==expression,'BOOTSTRAP_STATE_FIREWALL_UNRECOGNIZED')
    return True


def nft_client_guard_valid(document,source):
    objects=[nft_normalize(x) for x in document.get('nftables',[]) if 'metainfo' not in x]
    require(len(objects)==5,'BOOTSTRAP_STATE_CLIENT_FIREWALL_UNRECOGNIZED')
    require(objects[0].get('table')=={'family':'inet','name':'eatwhat_control_state_client'})
    chains={obj['chain']['name']:obj['chain'] for obj in objects[1:3] if 'chain' in obj}
    for name in ['output','forward']:
        require(chains.get(name)=={'family':'inet','table':'eatwhat_control_state_client','name':name,
            'type':'filter','hook':name,'prio':-10,'policy':'accept'})
    def match(left,op,right):return {'match':{'op':op,'left':left,'right':right}}
    base=[match({'payload':{'protocol':'ip','field':'daddr'}},'==',address(source)),
          match({'payload':{'protocol':'tcp','field':'dport'}},'==',2049)]
    for obj,name in zip(objects[3:],['output','forward']):
        expected=base+([match({'meta':{'key':'skuid'}},'!=',0)] if name=='output' else [])+[{'drop':None}]
        require(obj.get('rule')=={'family':'inet','table':'eatwhat_control_state_client','chain':name,'expr':expected},
                'BOOTSTRAP_STATE_CLIENT_FIREWALL_UNRECOGNIZED')
    return True


class Xdr:
    def __init__(self,data):self.data=data;self.offset=0
    def take(self,n):
        require(0<=n<=65536 and self.offset+n<=len(self.data),'BOOTSTRAP_STATE_RPC_TRUNCATED')
        value=self.data[self.offset:self.offset+n];self.offset+=n;return value
    def uint(self):return struct.unpack('!I',self.take(4))[0]
    def opaque(self,maximum=4096):
        n=self.uint();require(n<=maximum,'BOOTSTRAP_STATE_RPC_TOO_LARGE')
        value=self.take(n);self.take((-n)%4);return value


def uint(value):return struct.pack('!I',value)
def opaque(value):return uint(len(value))+value+b'\0'*((-len(value))%4)


class NfsReadProbe:
    def __init__(self,source,local,uid,gid,timeout=3):
        self.source=address(source);self.local=address(local);export_options(uid,gid)
        self.uid,self.gid,self.timeout=uid,gid,timeout

    def exchange(self,compound):
        deadline=time.monotonic()+self.timeout;xid=secrets.randbits(32)
        auth=uint(int(time.time())&0xffffffff)+opaque(b'eatwhat-state')+uint(self.uid)+uint(self.gid)+uint(0)
        packet=b''.join(uint(x) for x in [xid,0,2,100003,4,1,1])+opaque(auth)+uint(0)+opaque(b'')+compound
        with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as sock:
            bound=False
            for port in range(1023,599,-1):
                try:sock.bind((self.local,port));bound=True;break
                except OSError as error:
                    if error.errno!=errno.EADDRINUSE:raise
            require(bound,'BOOTSTRAP_STATE_NO_RESERVED_CLIENT_PORT')
            def limit():
                remaining=deadline-time.monotonic();require(remaining>0,'BOOTSTRAP_STATE_RPC_TIMEOUT');sock.settimeout(remaining)
            limit();sock.connect((self.source,2049));limit();sock.sendall(uint(0x80000000|len(packet))+packet)
            def receive(n):
                result=b''
                while len(result)<n:
                    limit();chunk=sock.recv(n-len(result));require(chunk,'BOOTSTRAP_STATE_RPC_CLOSED');result+=chunk
                return result
            chunks=[];size=0
            for _ in range(16):
                mark=struct.unpack('!I',receive(4))[0];n=mark&0x7fffffff;size+=n
                require(0<n<=16384 and size<=65536,'BOOTSTRAP_STATE_RPC_TOO_LARGE');chunks.append(receive(n))
                if mark&0x80000000:break
            else:raise Refusal('BOOTSTRAP_STATE_RPC_TOO_MANY_FRAGMENTS')
        reply=Xdr(b''.join(chunks))
        require(reply.uint()==xid and reply.uint()==1 and reply.uint()==0,'BOOTSTRAP_STATE_RPC_REJECTED')
        reply.uint();reply.opaque(512);require(reply.uint()==0,'BOOTSTRAP_STATE_RPC_REJECTED')
        return reply.data[reply.offset:]

    def compound(self,ops):
        permitted=ops==[(24,b''),(3,uint(63))]
        if len(ops)==3:
            permitted=ops[0]==(24,b'') and ops[1] in [(15,opaque(n.encode())) for n in ['controller-state-id','servers.key']] \
                and ops[2]==(25,b'\0'*16+struct.pack('!Q',0)+uint(64))
        require(permitted,'BOOTSTRAP_STATE_RPC_OPERATION_NOT_ALLOWED')
        packet=opaque(b'ew-state')+uint(0)+uint(len(ops))+b''.join(uint(op)+arg for op,arg in ops)
        reply=Xdr(self.exchange(packet));status=reply.uint();require(reply.opaque(32)==b'ew-state')
        count=reply.uint()
        if status!=0:raise NfsRefusal(status)
        require(count==len(ops),'BOOTSTRAP_STATE_NFS_OPERATION_REFUSED')
        results=[]
        for op,_ in ops:
            require(reply.uint()==op and reply.uint()==0,'BOOTSTRAP_STATE_NFS_OPERATION_REFUSED')
            if op==3:results.append((reply.uint(),reply.uint()))
            elif op==25:
                eof=reply.uint();require(eof in [0,1]);results.append((eof,reply.opaque(128)))
            else:require(op in [24,15])
        require(reply.offset==len(reply.data),'BOOTSTRAP_STATE_RPC_TRAILING_DATA');return results

    def identity(self,state_id,key_sha):
        require(str(uuid.UUID(state_id))==state_id and re.fullmatch(r'[a-f0-9]{64}',key_sha),
                'BOOTSTRAP_STATE_REMOTE_IDENTITY_INVALID')
        supported,allowed=self.compound([(24,b''),(3,uint(63))])[0]
        require((supported&15)==15 and (allowed&15)==15,'BOOTSTRAP_STATE_NFS_ROOT_NOT_WRITABLE')
        contents=[]
        for name,length in [('controller-state-id',36),('servers.key',32)]:
            eof,data=self.compound([(24,b''),(15,opaque(name.encode())),(25,b'\0'*16+struct.pack('!Q',0)+uint(64))])[0]
            require(eof==1 and len(data)==length,'BOOTSTRAP_STATE_REMOTE_IDENTITY_INVALID');contents.append(data)
        require(contents[0].decode()==state_id and hashlib.sha256(contents[1]).hexdigest()==key_sha,
                'BOOTSTRAP_STATE_REMOTE_IDENTITY_CHANGED')
        return {'stateIdentityVerified':True,'rootReadWriteAccess':True,'boundedReadOnlyRpc':True}
