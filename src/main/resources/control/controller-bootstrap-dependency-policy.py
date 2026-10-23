"""Exact forwarding-only SSH dependency policy. No filesystem/network mutation."""
import base64
import ipaddress
from pathlib import Path
import re
import uuid


class Refusal(Exception):
    def __init__(self,code):self.code=code;super().__init__(code)


def require(value,code='BOOTSTRAP_DEPENDENCY_POLICY_INVALID'):
    if not value:raise Refusal(code)


def identifier(value):
    try:require(str(uuid.UUID(value))==value and uuid.UUID(value).int!=0)
    except (ValueError,TypeError,AttributeError):raise Refusal('BOOTSTRAP_DEPENDENCY_ID_INVALID')
    return value


def address(value):
    try:
        ip=ipaddress.IPv4Address(value)
        require(str(ip)==value and any(ip in ipaddress.ip_network(n) for n in ['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16']))
    except (ValueError,TypeError):raise Refusal('BOOTSTRAP_DEPENDENCY_PRIVATE_ADDRESS_REQUIRED')
    return value


def public(value):
    require(isinstance(value,str) and re.fullmatch(r'ssh-ed25519 [A-Za-z0-9+/]+={0,2}',value),'BOOTSTRAP_DEPENDENCY_KEY_INVALID')
    try:wire=base64.b64decode(value.split()[1],validate=True)
    except ValueError:raise Refusal('BOOTSTRAP_DEPENDENCY_KEY_INVALID')
    require(len(wire)==51 and wire[:19]==b'\0\0\0\x0bssh-ed25519\0\0\0\x20','BOOTSTRAP_DEPENDENCY_KEY_INVALID')
    return value


def port(value):
    require(type(value) is int and 1024<=value<=65535,'BOOTSTRAP_DEPENDENCY_PORT_INVALID');return value


def listener_address(value):
    # Java may bind 127.0.0.1 through an AF_INET6 socket. ss then reports
    # [::ffff:127.0.0.1]:PORT, which has exactly the same IPv4 destination.
    # Do not equate ::1, wildcard or scoped interfaces with that destination.
    require(isinstance(value,str) and len(value)<=256,'BOOTSTRAP_DEPENDENCY_LISTENER_UNOBSERVABLE')
    host,separator,number=value.rpartition(':')
    require(separator and number.isascii() and number.isdigit() and 0<int(number)<65536,
        'BOOTSTRAP_DEPENDENCY_LISTENER_UNOBSERVABLE')
    try:ip=ipaddress.ip_address(host[1:-1] if host.startswith('[') and host.endswith(']') else host)
    except ValueError:return value
    if isinstance(ip,ipaddress.IPv6Address) and ip.ipv4_mapped is not None and ip.scope_id is None:
        return str(ip.ipv4_mapped)+':'+number
    return value


def forwards(values):
    require(isinstance(values,list) and 2<=len(values)<=19,'BOOTSTRAP_DEPENDENCY_FORWARDS_INVALID')
    purposes=set();local=set();remote=set();result=[]
    for row in values:
        require(isinstance(row,dict) and set(row)=={'purpose','localPort','remotePort'},'BOOTSTRAP_DEPENDENCY_FORWARDS_INVALID')
        require(isinstance(row['purpose'],str) and (row['purpose'] in ['CONTROL_DATABASE','CONTROL_CACHE','AUTHORITY_HTTP','SOURCE_ADMIN_SSH']
            or re.fullmatch(r'MEMBER_HTTP_[0-9]{1,4}',row['purpose'])) and row['purpose'] not in purposes,
            'BOOTSTRAP_DEPENDENCY_FORWARDS_INVALID')
        if row['purpose'].startswith('MEMBER_HTTP_'):
            slot=row['purpose'][len('MEMBER_HTTP_'):];require(str(int(slot))==slot and 1<=int(slot)<=1023,'BOOTSTRAP_DEPENDENCY_FORWARDS_INVALID')
        a=port(row['localPort']);b=row['remotePort']
        if row['purpose']=='SOURCE_ADMIN_SSH' or row['purpose']=='AUTHORITY_HTTP' or row['purpose'].startswith('MEMBER_HTTP_'):
            require(type(b) is int and 1<=b<=65535,'BOOTSTRAP_DEPENDENCY_PORT_INVALID')
        else:b=port(b)
        require(a not in local and b not in remote and a not in [18080,18081,2223]
            and (row['purpose']=='SOURCE_ADMIN_SSH' or b not in [18080,18081,2223]),'BOOTSTRAP_DEPENDENCY_PORT_COLLISION')
        purposes.add(row['purpose']);local.add(a);remote.add(b);result.append(dict(row))
    require({'CONTROL_DATABASE','CONTROL_CACHE'}<=purposes,'BOOTSTRAP_DEPENDENCY_FORWARDS_INVALID')
    return sorted(result,key=lambda x:x['purpose'])


def account(target_id):return 'ewctl-'+uuid.UUID(identifier(target_id)).hex[:16]
def unit_name(id,role):
    require(role in ['source','target']);return 'eatwhat-dependency-'+role+'-'+identifier(id)+'.service'
def runtime_name(id):return 'eatwhat-dependency-'+identifier(id)


def authorized_key(key,peer,ports):
    options='from="'+address(peer)+'",restrict,port-forwarding,command="/bin/false"'
    options+=''.join(',permitopen="127.0.0.1:'+str(x['remotePort'])+'"' for x in forwards(ports))
    return (options+' '+public(key)+'\n').encode()


def server_config(id,target_id,source,peer,listen_port,ports,home,task):
    user=account(target_id);address(peer);address(source);port(listen_port);forwards(ports)
    require(str(home)=='/var/lib/eatwhat-controller-dependency-accounts/'+target_id)
    require(str(task)=='/var/lib/eatwhat-controller-bootstrap-dependency/'+identifier(id))
    return ('''# Dedicated private forwarding endpoint; default sshd is unchanged.
AddressFamily inet
Port '''+str(listen_port)+'''
ListenAddress '''+source+'''
HostKey /etc/ssh/ssh_host_ed25519_key
PidFile '''+str(task/'server.pid')+'''
UsePAM yes
UseDNS no
StrictModes yes
AllowUsers '''+user+'@'+peer+'''
AllowGroups '''+user+'''
AuthorizedKeysFile none
AuthorizedKeysCommand /usr/bin/cat '''+str(task/'authorized_keys')+'''
AuthorizedKeysCommandUser root
TrustedUserCAKeys none
AuthenticationMethods publickey
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
HostbasedAuthentication no
GSSAPIAuthentication no
KerberosAuthentication no
PermitEmptyPasswords no
PermitRootLogin no
AllowTcpForwarding local
AllowStreamLocalForwarding no
PermitOpen '''+' '.join('127.0.0.1:'+str(x['remotePort']) for x in forwards(ports))+'''
PermitListen none
MaxSessions 0
PermitTTY no
AllowAgentForwarding no
X11Forwarding no
PermitTunnel no
PermitUserEnvironment no
PermitUserRC no
ForceCommand /bin/false
GatewayPorts no
LoginGraceTime 15
MaxStartups 3:50:5
ClientAliveInterval 15
ClientAliveCountMax 3
LogLevel ERROR
''').encode()


def server_unit(id,peer,task):
    address(peer);identifier(id)
    require(str(task)=='/var/lib/eatwhat-controller-bootstrap-dependency/'+id)
    return ('''[Unit]
Description=Private forwarding-only EatWhat dependency SSH
Requires=wg-quick@ewctrl0.service
After=network-online.target wg-quick@ewctrl0.service
BindsTo=wg-quick@ewctrl0.service
[Service]
Type=exec
ExecStart=/usr/sbin/sshd -D -e -f '''+str(task/'server.conf')+'''
RuntimeDirectory=sshd
RuntimeDirectoryMode=0755
RuntimeDirectoryPreserve=yes
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths='''+str(task)+'''
IPAddressDeny=any
IPAddressAllow=127.0.0.0/8
IPAddressAllow=::1/128
IPAddressAllow='''+peer+'''/32
[Install]
WantedBy=multi-user.target
''').encode()


def client_args(id,target_id,source,source_port,ports,key_path,hosts_path):
    identifier(id);address(source);port(source_port);forwards(ports)
    base=Path('/opt/eatwhat-control/private');require(key_path==base/(id+'.key') and hosts_path==base/(id+'.known-hosts'))
    args=['/usr/bin/ssh','-F','/dev/null','-M','-N','-T','-S','/run/'+runtime_name(id)+'/control',
        '-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(hosts_path),
        '-o','GlobalKnownHostsFile=/dev/null','-o','HostKeyAlias=dependency-'+id,'-o','HostKeyAlgorithms=ssh-ed25519',
        '-o','IdentitiesOnly=yes','-o','IdentityAgent=none','-o','ExitOnForwardFailure=yes',
        '-o','ServerAliveInterval=10','-o','ServerAliveCountMax=2','-o','ConnectTimeout=8','-o','LogLevel=ERROR',
        '-i',str(key_path),'-p',str(source_port)]
    for row in forwards(ports):args+=['-L','127.0.0.1:'+str(row['localPort'])+':127.0.0.1:'+str(row['remotePort'])]
    return args+['--',account(target_id)+'@'+source]


def client_unit(id,target_id,source,source_port,ports,key_path,hosts_path):
    args=client_args(id,target_id,source,source_port,ports,key_path,hosts_path)
    require(all(re.fullmatch(r'[a-zA-Z0-9_./:@=,-]+',a) for a in args),'BOOTSTRAP_DEPENDENCY_EXEC_INVALID')
    return ('''[Unit]
Description=Encrypted EatWhat dependency forwarding over the private link
Requires=wg-quick@ewctrl0.service
After=network-online.target wg-quick@ewctrl0.service
BindsTo=wg-quick@ewctrl0.service
[Service]
Type=exec
User=eatwhat-control
Group=eatwhat-control
ExecStart='''+' '.join(args)+'''
RuntimeDirectory='''+runtime_name(id)+'''
RuntimeDirectoryMode=0700
Restart=always
RestartSec=3
UMask=0077
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
IPAddressDeny=any
IPAddressAllow=127.0.0.0/8
IPAddressAllow=::1/128
IPAddressAllow='''+source+'''/32
[Install]
WantedBy=multi-user.target
''').encode()
