"""Pure validation/rendering for the approved management-controller bundle.

The manager still has to collect/freeze source configuration and validate admin,
leadership, epoch and registered-host revisions. HTTP never supplies this bundle.
"""
import base64
import hashlib
import ipaddress
import json
from pathlib import Path,PurePosixPath
import re
import tarfile
from urllib.parse import urlsplit,parse_qsl
import uuid
import zipfile

CONTROL=Path('/opt/eatwhat-control')
PROTOCOL='eatwhat-controller-bootstrap-controller-v1'
MARKER='META-INF/'+PROTOCOL
MAX_JAR=256*1024*1024
MAX_UI_ARCHIVE=16*1024*1024
MAX_UI_CONTENT=16*1024*1024
MAX_IMAGE_ARCHIVE=2*1024*1024*1024
MAX_STREAM=16*1024*1024

class Refusal(Exception):
    def __init__(self,code):self.code=code;super().__init__(code)

def require(value,code='BOOTSTRAP_CONTROLLER_BUNDLE_INVALID'):
    if not value:raise Refusal(code)

def canonical(v):return json.dumps(v,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def digest(v):return hashlib.sha256(v).hexdigest()
def identifier(v):
    try:require(str(uuid.UUID(v))==v and uuid.UUID(v).int!=0)
    except (ValueError,TypeError,AttributeError):raise Refusal('BOOTSTRAP_CONTROLLER_ID_INVALID')
    return v
def sha(v):require(isinstance(v,str) and re.fullmatch('[a-f0-9]{64}',v));return v
def label(v):require(isinstance(v,str) and re.fullmatch('[A-Za-z0-9_-]{1,64}',v));return v
def listener_address(value):
    # Also support an already sealed older dependency observer, whose ss rows
    # predate the IPv4-mapped Java listener fix. No binding/receipt is replaced.
    host,separator,number=value.rpartition(':')
    require(separator and number.isascii() and number.isdigit() and 0<int(number)<65536)
    try:ip=ipaddress.ip_address(host[1:-1] if host.startswith('[') and host.endswith(']') else host)
    except ValueError:return value
    if isinstance(ip,ipaddress.IPv6Address) and ip.ipv4_mapped is not None and ip.scope_id is None:return str(ip.ipv4_mapped)+':'+number
    return value
def controller_name(target_id):return 'control-'+uuid.UUID(identifier(target_id)).hex[:24]

def resource_name(v):
    require(isinstance(v,str) and 0<len(v)<=180 and re.fullmatch(r'[a-zA-Z0-9_./-]+',v)
        and str(PurePosixPath(v))==v and not PurePosixPath(v).is_absolute()
        and '..' not in PurePosixPath(v).parts,'BOOTSTRAP_CONTROLLER_UI_PATH_INVALID')
    return v

def escape_property(v,key=False):
    # ASCII output has the same meaning to java.util.Properties and Spring's
    # UTF-8 loader. Newlines/backslashes cannot inject an extra setting.
    result=[]
    for char in v:
        point=ord(char)
        if char=='\\':result.append('\\\\')
        elif char in '\n\r\t\f':result.append({'\n':'\\n','\r':'\\r','\t':'\\t','\f':'\\f'}[char])
        elif char in ' =:#!' and (key or not result):result.append('\\'+char)
        elif point<32 or point>126:
            for offset in range(0,len(raw:=char.encode('utf-16-be')),2):result.append('\\u'+raw[offset:offset+2].hex())
        else:result.append(char)
    return ''.join(result)

def properties_bytes(values):
    require(isinstance(values,dict) and 1<=len(values)<=1024)
    lines=[];normalized=set()
    for key,value in sorted(values.items()):
        # Only canonical keys enter the bundle, so Spring relaxed-binding aliases
        # cannot undo a reviewed process identity or disable a safety setting.
        require(isinstance(key,str) and len(key)<=200 and (re.fullmatch(r'[a-z0-9]+(?:[.\-][a-z0-9]+|\[[0-9]+\])*',key)
            or re.fullmatch(r'app\.control\.releases\[[A-Za-z0-9_-]{1,64}\]\.(artifact|sha256|acceptance-evidence-id|rollback-compatible)',key)),
            'BOOTSTRAP_CONTROLLER_PROPERTY_KEY_INVALID')
        require(isinstance(value,str) and len(value)<=32768 and '\0' not in value and '${' not in value,
            'BOOTSTRAP_CONTROLLER_PROPERTY_VALUE_INVALID')
        release=re.fullmatch(r'app\.control\.releases(?:\[([A-Za-z0-9_-]{1,64})\]|\.([A-Za-z0-9_-]{1,64}))\.([a-z0-9-]+)',key)
        relaxed=('appcontrolreleases['+(release[1] or release[2])+']'+release[3].replace('-','')) if release else re.sub(r'[.\-\[\]]','',key)
        require(relaxed not in normalized,'BOOTSTRAP_CONTROLLER_RELAXED_ALIAS_FORBIDDEN');normalized.add(relaxed)
        require(not key.startswith(('spring.config.','spring.profiles.','spring.main.','spring.application.json','logging.config')),
            'BOOTSTRAP_CONTROLLER_DYNAMIC_CONFIGURATION_FORBIDDEN')
        lines.append(escape_property(key,True)+'='+escape_property(value))
    result=('\n'.join(lines)+'\n').encode();require(len(result)<=256*1024);return result

def file_contents(values):
    require(isinstance(values,dict) and 1<=len(values)<=128)
    result={}
    for name,value in values.items():
        require(isinstance(name,str) and re.fullmatch(r'[a-z0-9][a-z0-9_.-]{0,95}',name)
            and name not in ['application.properties','.','..'],'BOOTSTRAP_CONTROLLER_PRIVATE_FILENAME_INVALID')
        try:raw=base64.b64decode(value,validate=True)
        except (ValueError,TypeError):raise Refusal('BOOTSTRAP_CONTROLLER_PRIVATE_FILE_INVALID')
        require(0<len(raw)<=256*1024,'BOOTSTRAP_CONTROLLER_PRIVATE_FILE_INVALID');result[name]=raw
    require(sum(map(len,result.values()))<=512*1024);return result

def dependency_ports(values):
    require(isinstance(values,list) and 3<=len(values)<=19)
    result={}
    for row in values:
        require(set(row)=={'purpose','localPort','remotePort'} and isinstance(row['purpose'],str) and (row['purpose'] in ['CONTROL_DATABASE','CONTROL_CACHE','AUTHORITY_HTTP','SOURCE_ADMIN_SSH']
            or re.fullmatch(r'MEMBER_HTTP_[0-9]{1,4}',row['purpose'])))
        require(row['purpose'] not in result and type(row['localPort']) is int and 1024<=row['localPort']<=65535
            and type(row['remotePort']) is int and 1<=row['remotePort']<=65535)
        if row['purpose'].startswith('MEMBER_HTTP_'):
            slot=row['purpose'][len('MEMBER_HTTP_'):];require(str(int(slot))==slot and 1<=int(slot)<=1023)
        result[row['purpose']]=row['localPort']
    require({'CONTROL_DATABASE','CONTROL_CACHE','AUTHORITY_HTTP'}<=result.keys() and len(set(result.values()))==len(values));return result

def database(values,ports):
    allowed={'spring.datasource.url','spring.datasource.username','spring.datasource.password','spring.datasource.driver-class-name',
        'spring.datasource.hikari.maximum-pool-size','spring.datasource.hikari.minimum-idle','spring.datasource.hikari.connection-timeout',
        'spring.datasource.hikari.validation-timeout','spring.datasource.hikari.idle-timeout','spring.datasource.hikari.max-lifetime',
        'spring.datasource.hikari.pool-name'}
    require(all(not k.startswith('spring.datasource.') or k in allowed for k in values),
        'BOOTSTRAP_CONTROLLER_ALTERNATE_DATASOURCE_FORBIDDEN')
    require(values.get('spring.datasource.driver-class-name','org.postgresql.Driver')=='org.postgresql.Driver')
    text=values.get('spring.datasource.url','')
    require(text.startswith('jdbc:postgresql://'),'BOOTSTRAP_CONTROLLER_DATABASE_INVALID')
    try:uri=urlsplit(text[5:]);port=uri.port
    except ValueError:raise Refusal('BOOTSTRAP_CONTROLLER_DATABASE_INVALID')
    require(uri.scheme=='postgresql' and uri.hostname=='127.0.0.1' and port==ports['CONTROL_DATABASE']
        and uri.username is None and uri.password is None and uri.fragment=='' and re.fullmatch('/[A-Za-z0-9_-]{1,63}',uri.path),
        'BOOTSTRAP_CONTROLLER_DATABASE_INVALID')
    allowed={'sslmode','sslrootcert','sslcert','sslkey','sslpassword','connectTimeout','socketTimeout','ApplicationName'}
    query=parse_qsl(uri.query,keep_blank_values=True);require(len(query)==len(dict(query)) and {k for k,v in query}<=allowed,
        'BOOTSTRAP_CONTROLLER_DATABASE_OPTIONS_INVALID')
    for key,value in query:
        if key in ['sslrootcert','sslcert','sslkey']:require(value.startswith(str(CONTROL/'config')+'/') and '/' not in value[len(str(CONTROL/'config'))+1:])
    require(values.get('spring.datasource.username','').strip() and values.get('spring.datasource.password',''),
        'BOOTSTRAP_CONTROLLER_DATABASE_CREDENTIALS_REQUIRED')
    require(values.get('spring.data.redis.host')=='127.0.0.1' and values.get('spring.data.redis.port')==str(ports['CONTROL_CACHE'])
        and 'spring.data.redis.url' not in values and not any(k.startswith(('spring.data.redis.sentinel.','spring.data.redis.cluster.')) for k in values),
        'BOOTSTRAP_CONTROLLER_CACHE_INVALID')

def validate_bundle(data,prior,forwards,authority):
    # prior is the original private-peer request obtained via the verified
    # dependency/NFS chain, never a caller-controlled replacement identity.
    keys={'clusterId','authoritySiteId','authorityInstanceId','authorityDatabaseFingerprint',
        'controllerName','controllerSha256','controllerSize','uiSha256','uiSize','uiFiles','properties','privateFiles'}
    require(isinstance(data,dict) and set(data) in [keys,keys|{'catalog'}])
    identifier(data['clusterId']);identifier(data['authorityInstanceId']);label(data['authoritySiteId'])
    require(isinstance(data['authorityDatabaseFingerprint'],str) and re.fullmatch('[a-f0-9]{32}',data['authorityDatabaseFingerprint']))
    require(isinstance(authority,dict) and set(authority)=={'clusterId','siteId','instanceId','databaseFingerprint'},
        'BOOTSTRAP_CONTROLLER_SOURCE_IDENTITY_REQUIRED')
    require(all(data[k]==authority[v] for k,v in [('clusterId','clusterId'),('authoritySiteId','siteId'),
        ('authorityInstanceId','instanceId'),('authorityDatabaseFingerprint','databaseFingerprint')]),
        'BOOTSTRAP_CONTROLLER_SOURCE_IDENTITY_CHANGED')
    require(data['controllerName']==controller_name(prior['targetServerId']),'BOOTSTRAP_CONTROLLER_PROCESS_IDENTITY_INVALID')
    require(sha(data['controllerSha256'])==prior['controllerArtifactSha256'],'BOOTSTRAP_CONTROLLER_ARTIFACT_CHANGED')
    sha(data['uiSha256'])
    for key,maximum in [('controllerSize',MAX_JAR),('uiSize',MAX_UI_ARCHIVE)]:require(type(data[key]) is int and 0<data[key]<=maximum)
    require(isinstance(data['uiFiles'],dict) and 5<=len(data['uiFiles'])<=256)
    for name,value in data['uiFiles'].items():resource_name(name);sha(value)
    require({'index.html','api.js','vendor/opencc/t2cn.js','vendor/opencc/LICENSE','vendor/opencc/THIRD_PARTY_LICENSES.md'}<=data['uiFiles'].keys(),
        'BOOTSTRAP_CONTROLLER_UI_INCOMPLETE')
    values=data['properties'];properties_bytes(values);private=file_contents(data['privateFiles']);ports=dependency_ports(forwards)
    required={'server.address':'127.0.0.1','server.port':'18081','app.control.enabled':'true','app.control.node-enabled':'true',
        'app.control.environment-kind':'managed-deployment','app.control.state-directory':str(CONTROL/'state'),'app.control.shared-state-required':'true',
        'app.cluster.enabled':'true','app.cluster.coordinator':'true','app.cluster.single-site-only':'false',
        'app.cluster.control-lease.enabled':'true','app.cluster.control-lease.candidate':'true','app.cluster.control-lease.automatic-takeover':'false',
        'app.cluster.control-lease.controller-name':data['controllerName'],'app.site.cluster-id':data['clusterId'],
        'app.site.id':data['authoritySiteId'],'app.site.instance-id':data['authorityInstanceId'],'app.site.mode':'CAMPUS','app.site.allocation-slot':'0',
        'app.cluster.profiles-file':str(CONTROL/'config/cluster-profiles.json'),
        'app.control.ui-enabled':'true','spring.web.resources.static-locations':'file:/srv/eatwhat-control-releases/ui-'+data['uiSha256']+'/',
        'spring.mvc.static-path-pattern':'/control-ui/**','spring.flyway.enabled':'true','spring.sql.init.mode':'never',
        'app.control.database-mode':'existing-authority','app.control.authority-database-fingerprint':data['authorityDatabaseFingerprint']}
    require(all(values.get(k)==v for k,v in required.items()),'BOOTSTRAP_CONTROLLER_REQUIRED_CONFIGURATION_CHANGED')
    normalized={re.sub('[.\\-]','',k):k for k in required}
    require(all(normalized.get(re.sub('[.\\-]','',key),key)==key for key in values),
        'BOOTSTRAP_CONTROLLER_RELAXED_ALIAS_FORBIDDEN')
    require(not any(k.startswith(('app.control.proxies.','app.cluster.profiles.')) for k in values),'BOOTSTRAP_CONTROLLER_UNREVIEWED_LOCAL_ENDPOINT')
    require(not any(k.startswith(('spring.liquibase.','spring.batch.jdbc.')) or k.startswith('spring.flyway.') and k!='spring.flyway.enabled'
        or k.startswith('spring.sql.init.') and k!='spring.sql.init.mode' for k in values),'BOOTSTRAP_CONTROLLER_SCHEMA_INITIALIZATION_FORBIDDEN')
    database(values,ports)
    require('cluster-profiles.json' in private,'BOOTSTRAP_CONTROLLER_PROFILES_REQUIRED')
    try:profiles=json.loads(private['cluster-profiles.json'])
    except (ValueError,TypeError):raise Refusal('BOOTSTRAP_CONTROLLER_PROFILES_INVALID')
    require(isinstance(profiles,dict) and 1<=len(profiles)<=1024,'BOOTSTRAP_CONTROLLER_PROFILES_INVALID')
    authority=[];seen_sites=set();seen_instances=set();seen_slots=set();seen_origins=set();used_forwards=set()
    for name,profile in profiles.items():
        label(name);require(isinstance(profile,dict) and profile.get('siteId') and profile.get('instanceId')
            and set(profile)<={'siteId','instanceId','mode','allocationSlot','origin','apiPrefix','tokenFile','channels','caFile'})
        label(profile['siteId']);identifier(profile['instanceId'])
        require(profile.get('mode') in ['CAMPUS','EXTERNAL'] and type(profile.get('allocationSlot')) is int
            and 0<=profile['allocationSlot']<=1023,'BOOTSTRAP_CONTROLLER_PROFILE_IDENTITY_INVALID')
        try:
            uri=urlsplit(profile.get('origin',''));uri.port
            require(uri.scheme=='https' or uri.scheme=='http' and uri.hostname=='127.0.0.1')
            require(uri.hostname and uri.username is None and uri.password is None and uri.path in ['', '/'] and not uri.query and not uri.fragment)
        except (ValueError,TypeError):raise Refusal('BOOTSTRAP_CONTROLLER_PROFILE_ORIGIN_INVALID')
        origin=profile['origin'].rstrip('/')
        require(profile['siteId'] not in seen_sites and profile['instanceId'] not in seen_instances
            and profile['allocationSlot'] not in seen_slots and origin not in seen_origins,'BOOTSTRAP_CONTROLLER_DUPLICATE_PROFILE')
        seen_sites.add(profile['siteId']);seen_instances.add(profile['instanceId']);seen_slots.add(profile['allocationSlot']);seen_origins.add(origin)
        prefix=profile.get('apiPrefix','');require(prefix in ['', '/'] or isinstance(prefix,str) and len(prefix)<=128
            and re.fullmatch('/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?',prefix),'BOOTSTRAP_CONTROLLER_PROFILE_PREFIX_INVALID')
        channels=profile.get('channels',{});require(isinstance(channels,dict) and len(channels)<=32)
        for key,value in channels.items():label(key);require(type(value) is int and 1<=value<=300)
        token=profile.get('tokenFile','');require(token.startswith(str(CONTROL/'config')+'/'))
        filename=token[len(str(CONTROL/'config'))+1:];require(filename in private and re.fullmatch(rb'[A-Za-z0-9_-]{43,128}\s*',private[filename]))
        ca=profile.get('caFile')
        if ca is not None:
            require(isinstance(ca,str) and uri.scheme=='https' and ca.startswith(str(CONTROL/'config')+'/'),'BOOTSTRAP_CONTROLLER_PROFILE_CA_INVALID')
            name=ca[len(str(CONTROL/'config'))+1:];require('/' not in name and name in private,'BOOTSTRAP_CONTROLLER_PROFILE_CA_INVALID')
            raw=private[name].strip();require(len(raw)<=65536 and raw.startswith(b'-----BEGIN CERTIFICATE-----') and raw.endswith(b'-----END CERTIFICATE-----') and b'PRIVATE KEY' not in raw,'BOOTSTRAP_CONTROLLER_PROFILE_CA_INVALID')
        if profile['siteId']==data['authoritySiteId']:authority.append(profile)
        elif uri.hostname=='127.0.0.1':
            purpose='MEMBER_HTTP_'+str(profile['allocationSlot'])
            require(purpose in ports and uri.port==ports[purpose],'BOOTSTRAP_CONTROLLER_MEMBER_ROUTE_MISMATCH');used_forwards.add(purpose)
    require(used_forwards=={name for name in ports if name.startswith('MEMBER_HTTP_')},'BOOTSTRAP_CONTROLLER_MEMBER_ROUTE_MISMATCH')
    require(len(authority)==1 and authority[0]['instanceId']==data['authorityInstanceId'] and authority[0].get('mode')=='CAMPUS'
        and authority[0].get('allocationSlot')==0,'BOOTSTRAP_CONTROLLER_AUTHORITY_PROFILE_MISMATCH')
    origin=authority[0].get('origin','')
    require(values.get('app.control.node-token-file')==authority[0]['tokenFile'],
        'BOOTSTRAP_CONTROLLER_AUTHORITY_NODE_TOKEN_CHANGED')
    require(origin in ['http://127.0.0.1:'+str(ports['AUTHORITY_HTTP']),'https://127.0.0.1:'+str(ports['AUTHORITY_HTTP'])]
        and values.get('app.control.deployment.authority-origin')==origin,'BOOTSTRAP_CONTROLLER_AUTHORITY_ROUTE_MISMATCH')
    validate_catalog(data,private)
    return {'properties':properties_bytes(values),'privateFiles':private}

def catalog(data):
    if 'catalog' not in data:return None
    c=data['catalog']
    keys={'version','releaseId','controllerAcceptanceEvidenceId','businessAcceptanceEvidenceId',
        'businessSha256','businessSize','manifestSha256','manifestSize','images','imageArchive'}
    require(isinstance(c,dict) and type(c.get('version')) is int and (c['version']==2 and set(c)==keys
        or c['version']==3 and set(c)==keys|{'releases'}),'BOOTSTRAP_CONTROLLER_CATALOG_INVALID')
    label(c['releaseId'])
    for name in ['controllerAcceptanceEvidenceId','businessAcceptanceEvidenceId']:
        require(isinstance(c[name],str) and 0<len(c[name])<=128 and re.fullmatch(r'[A-Za-z0-9_.:/-]+',c[name]),'BOOTSTRAP_CONTROLLER_CATALOG_ACCEPTANCE_REQUIRED')
    for name in ['businessSha256','manifestSha256']:sha(c[name])
    for name,maximum in [('businessSize',MAX_JAR),('manifestSize',65536)]:
        require(type(c[name]) is int and 0<c[name]<=maximum,'BOOTSTRAP_CONTROLLER_CATALOG_SIZE_INVALID')
    if c['version']==3:
        require(isinstance(c['releases'],dict) and 1<=len(c['releases'])<=64,'BOOTSTRAP_CONTROLLER_RELEASE_CATALOG_INVALID')
        sizes={}
        for name,release in c['releases'].items():
            label(name);require(isinstance(release,dict) and set(release)=={'sha256','size','acceptanceEvidenceId','rollbackCompatible'},'BOOTSTRAP_CONTROLLER_RELEASE_CATALOG_INVALID')
            sha(release['sha256']);require(type(release['size']) is int and 0<release['size']<=MAX_JAR and type(release['rollbackCompatible']) is bool,
                'BOOTSTRAP_CONTROLLER_RELEASE_CATALOG_INVALID')
            value=release['acceptanceEvidenceId'];require(isinstance(value,str) and 0<len(value)<=128 and re.fullmatch(r'[A-Za-z0-9_.:/-]+',value),'BOOTSTRAP_CONTROLLER_CATALOG_ACCEPTANCE_REQUIRED')
            require(sizes.setdefault(release['sha256'],release['size'])==release['size'],'BOOTSTRAP_CONTROLLER_RELEASE_CATALOG_INVALID')
        selected=c['releases'].get(c['releaseId']);require(selected is not None and selected['sha256']==c['businessSha256']
            and selected['size']==c['businessSize'] and selected['acceptanceEvidenceId']==c['businessAcceptanceEvidenceId'],
            'BOOTSTRAP_CONTROLLER_RELEASE_CATALOG_INVALID')
    require(isinstance(c['images'],dict) and set(c['images'])=={'jre','postgres','redis'},'BOOTSTRAP_CONTROLLER_CATALOG_IMAGES_INVALID')
    for value in c['images'].values():require(isinstance(value,str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9./:@_-]{0,199}',value),'BOOTSTRAP_CONTROLLER_CATALOG_IMAGES_INVALID')
    if c['imageArchive'] is not None:
        a=c['imageArchive'];require(isinstance(a,dict) and set(a)=={'sha256','size'},'BOOTSTRAP_CONTROLLER_CATALOG_INVALID');sha(a['sha256'])
        require(type(a['size']) is int and 0<a['size']<=MAX_IMAGE_ARCHIVE,'BOOTSTRAP_CONTROLLER_CATALOG_SIZE_INVALID')
    return c

def releases(c):
    if c['version']==3:return c['releases']
    return {c['releaseId']:{'sha256':c['businessSha256'],'size':c['businessSize'],
        'acceptanceEvidenceId':c['businessAcceptanceEvidenceId'],'rollbackCompatible':False}}

def release_kind(c,release):return 'BUSINESS' if release['sha256']==c['businessSha256'] else 'RELEASE_'+release['sha256']

def artifact_specs(data):
    # Names and targets are derived, never caller-provided paths. Legacy sealed
    # inputs keep exactly their two original blob kinds.
    result={'JAR':{'sha256':sha(data['controllerSha256']),'size':data['controllerSize'],'filename':data['controllerSha256']+'.jar'},
        'UI':{'sha256':sha(data['uiSha256']),'size':data['uiSize'],'filename':data['uiSha256']+'.tar.gz'}}
    c=catalog(data)
    if c is not None:
        result['BUSINESS']={'sha256':c['businessSha256'],'size':c['businessSize'],'filename':c['businessSha256']+'.business.jar'}
        result['UI_MANIFEST']={'sha256':c['manifestSha256'],'size':c['manifestSize'],'filename':c['manifestSha256']+'.json'}
        if c['imageArchive'] is not None:result['IMAGES']={**c['imageArchive'],'filename':c['imageArchive']['sha256']+'.images.tar'}
        for name,release in sorted(releases(c).items()):
            result.setdefault(release_kind(c,release),{'sha256':release['sha256'],'size':release['size'],'filename':release['sha256']+'.business.jar'})
    return result

def catalog_properties(data):
    c=catalog(data);require(c is not None,'BOOTSTRAP_CONTROLLER_CATALOG_REQUIRED');paths=artifact_specs(data);base=str(CONTROL/'acceptance')+'/'
    prefix='app.control.controller-bootstrap.'
    result={prefix+'enabled':'true',prefix+'controller-artifact':base+paths['JAR']['filename'],prefix+'controller-sha256':data['controllerSha256'],
        prefix+'ui-archive':base+paths['UI']['filename'],prefix+'ui-sha256':data['uiSha256'],prefix+'ui-manifest':base+paths['UI_MANIFEST']['filename'],
        prefix+'acceptance-evidence-id':c['controllerAcceptanceEvidenceId'],
        'app.control.deployment.controller-artifact':base+paths['JAR']['filename'],'app.control.deployment.enabled':'true',
        'app.control.deployment.target-source':'REGISTERED_SERVERS','app.control.servers.installation-enabled':'true','app.control.servers.cluster-enrollment-enabled':'true'}
    for key,value in c['images'].items():result['app.control.servers.'+key+'-image']=value
    if 'IMAGES' in paths:
        result['app.control.servers.image-archive']=base+paths['IMAGES']['filename'];result['app.control.servers.image-archive-sha256']=c['imageArchive']['sha256']
    for name,entry in sorted(releases(c).items()):
        release='app.control.releases['+name+'].'
        result.update({release+'artifact':base+paths[release_kind(c,entry)]['filename'],release+'sha256':entry['sha256'],
            release+'acceptance-evidence-id':entry['acceptanceEvidenceId'],release+'rollback-compatible':str(entry['rollbackCompatible']).lower()})
    return result

def validate_catalog(data,private):
    values=data['properties'];c=catalog(data)
    prefixes=('app.control.controller-bootstrap.','app.control.releases.','app.control.releases[','app.control.servers.image-archive')
    require(not any(k in values for k in ['app.control.releases','app.control.controller-bootstrap']),'BOOTSTRAP_CONTROLLER_CATALOG_CONFIGURATION_CHANGED')
    if c is None:
        require(not any(k.startswith(prefixes) for k in values),'BOOTSTRAP_CONTROLLER_CATALOG_REQUIRED');return
    wanted=catalog_properties(data)
    require(all(values.get(k)==v for k,v in wanted.items()),'BOOTSTRAP_CONTROLLER_CATALOG_CONFIGURATION_CHANGED')
    require(all(not k.startswith(prefixes) or k in wanted for k in values),'BOOTSTRAP_CONTROLLER_CATALOG_CONFIGURATION_CHANGED')
    # A registered-server controller must not retain SOURCE's static SSH paths.
    require(not any(k in ['app.control.deployment.identity-file','app.control.deployment.known-hosts-file']
        or k.startswith('app.control.deployment.targets.') for k in values),'BOOTSTRAP_CONTROLLER_SOURCE_PATH_RETAINED')
    for key in ['app.control.servers.credentials-file','app.control.servers.ssh-routes-file','app.control.servers.member-configurations-file',
            'app.control.node-ca-file','app.cluster.node-ca-file','app.control.deployment.authority-ca-file']:
        if key not in values:continue
        path=PurePosixPath(values[key]);require(path.parent==PurePosixPath(CONTROL/'config') and path.name in private,'BOOTSTRAP_CONTROLLER_PRIVATE_REFERENCE_MISSING')
    for key,value in parse_qsl(urlsplit(values['spring.datasource.url'][5:]).query,keep_blank_values=True):
        if key in ['sslrootcert','sslcert','sslkey']:
            path=PurePosixPath(value);require(path.parent==PurePosixPath(CONTROL/'config') and path.name in private,'BOOTSTRAP_CONTROLLER_PRIVATE_REFERENCE_MISSING')

def verify_catalog_files(paths,data):
    # Byte hashes/ownership are checked by the receiver. This checks semantics
    # without extracting image archives or executing a business JAR.
    c=catalog(data)
    if c is None:return
    def unique(pairs):
        result={}
        for key,value in pairs:require(key not in result,'BOOTSTRAP_CONTROLLER_MANIFEST_INVALID');result[key]=value
        return result
    try:
        raw=paths['UI_MANIFEST'].read_bytes();require(len(raw)<=65536,'BOOTSTRAP_CONTROLLER_MANIFEST_INVALID')
        manifest=json.loads(raw,object_pairs_hook=unique)
        require(isinstance(manifest,dict) and manifest.get('sha256')==data['uiSha256'] and manifest.get('files')==data['uiFiles'],
            'BOOTSTRAP_CONTROLLER_MANIFEST_INVALID')
        for kind in {release_kind(c,entry) for entry in releases(c).values()}:
            with zipfile.ZipFile(paths[kind]) as jar:
                require('BOOT-INF/classes/you/v50to/eatwhat/EatwhatApplication.class' in jar.namelist(),'BOOTSTRAP_CONTROLLER_BUSINESS_ARTIFACT_INVALID')
    except (ValueError,TypeError,KeyError,zipfile.BadZipFile):raise Refusal('BOOTSTRAP_CONTROLLER_CATALOG_CONTENT_INVALID')

def verify_jar(path,bundle):
    require(path.stat().st_size==bundle['controllerSize'] and digest(path.read_bytes())==bundle['controllerSha256'],
        'BOOTSTRAP_CONTROLLER_ARTIFACT_CHANGED')
    try:
        jar=zipfile.ZipFile(path)
        require(MARKER in jar.namelist(),'BOOTSTRAP_CONTROLLER_ARTIFACT_PROTOCOL_REQUIRED')
    except (zipfile.BadZipFile,KeyError):raise Refusal('BOOTSTRAP_CONTROLLER_ARTIFACT_PROTOCOL_REQUIRED')
    with jar:
        require(jar.read(MARKER)==(PROTOCOL+'\n').encode(),'BOOTSTRAP_CONTROLLER_ARTIFACT_PROTOCOL_REQUIRED')
        if bundle.get('properties',{}).get('app.control.controller-bootstrap.enabled')=='true':
            marker='META-INF/eatwhat-controller-profile-receiver-v1'
            require(jar.namelist().count(marker)==1 and jar.read(marker)==b'eatwhat-controller-profile-receiver-v1\n'
                and all('BOOT-INF/classes/you/v50to/eatwhat/control/'+name+'.class' in jar.namelist()
                        for name in ['ControlBootstrapProfileNodeController','ControlBootstrapProfileReceiver','ControlBootstrapProfileState']),
                'BOOTSTRAP_CONTROLLER_PROFILE_RECEIVER_REQUIRED')
            require('META-INF/eatwhat-controller-profile-services-successor-v1' in jar.namelist()
                and jar.read('META-INF/eatwhat-controller-profile-services-successor-v1')==b'eatwhat-controller-profile-services-successor-v1\n'
                and 'BOOT-INF/classes/you/v50to/eatwhat/control/ControlBootstrapProfileServices.class' in jar.namelist(),
                'BOOTSTRAP_CONTROLLER_PROFILE_SERVICES_CAPABILITY_REQUIRED')
            require('META-INF/eatwhat-controller-bootstrap-membership-v1' in jar.namelist()
                and jar.read('META-INF/eatwhat-controller-bootstrap-membership-v1')==b'eatwhat-controller-bootstrap-membership-v1\n'
                and all('BOOT-INF/classes/you/v50to/eatwhat/control/'+name+'.class' in jar.namelist()
                        for name in ['ControlBootstrapMembership','ControlBootstrapMembershipNodeController']),
                'BOOTSTRAP_CONTROLLER_MEMBERSHIP_CAPABILITY_REQUIRED')
            require('META-INF/eatwhat-controller-bootstrap-membership-v2' in jar.namelist()
                and jar.read('META-INF/eatwhat-controller-bootstrap-membership-v2')==b'eatwhat-controller-bootstrap-membership-v2\n'
                and 'BOOT-INF/classes/you/v50to/eatwhat/cluster/ClusterMembershipCoordinator$Boundary.class' in jar.namelist(),
                'BOOTSTRAP_CONTROLLER_MEMBERSHIP_CAPABILITY_REQUIRED')
            require('META-INF/eatwhat-controller-bootstrap-verify-v1' in jar.namelist()
                and jar.read('META-INF/eatwhat-controller-bootstrap-verify-v1')==b'eatwhat-controller-bootstrap-verify-v1\n'
                and all('BOOT-INF/classes/you/v50to/eatwhat/control/'+name+'.class' in jar.namelist() for name in ['ControlBootstrapVerify','ControlBootstrapMemberRuntime']),
                'BOOTSTRAP_CONTROLLER_VERIFY_CAPABILITY_REQUIRED')
        # Early artifact-catalog review has no private profile bundle yet.
        profiles=json.loads(base64.b64decode(bundle['privateFiles']['cluster-profiles.json'],validate=True)) if 'privateFiles' in bundle else {}
        if any(p.get('caFile') is not None for p in profiles.values()):
            require('META-INF/eatwhat-cluster-profile-ca-v1' in jar.namelist()
                and jar.read('META-INF/eatwhat-cluster-profile-ca-v1')==b'eatwhat-cluster-profile-ca-v1\n'
                and 'BOOT-INF/classes/you/v50to/eatwhat/config/NodeHttpClients$ScopedTrust.class' in jar.namelist(),
                'BOOTSTRAP_CONTROLLER_PROFILE_CA_CAPABILITY_REQUIRED')
        require('META-INF/eatwhat-manual-control-handoff-v1' in jar.namelist()
            and jar.read('META-INF/eatwhat-manual-control-handoff-v1')==b'eatwhat-manual-control-handoff-v1\n')
        require('BOOT-INF/classes/you/v50to/eatwhat/control/ControlUiController.class' in jar.namelist(),
            'BOOTSTRAP_CONTROLLER_UI_HANDLER_REQUIRED')
        require('BOOT-INF/classes/you/v50to/eatwhat/control/bootstrap/ControllerRuntimeProbe.class' in jar.namelist(),
            'BOOTSTRAP_CONTROLLER_RUNTIME_PROBE_REQUIRED')
        require('META-INF/eatwhat-controller-existing-authority-schema-v1' in jar.namelist()
            and jar.read('META-INF/eatwhat-controller-existing-authority-schema-v1')==b'eatwhat-controller-existing-authority-schema-v1\n'
            and 'BOOT-INF/classes/you/v50to/eatwhat/control/bootstrap/ControllerExistingAuthoritySchema.class' in jar.namelist(),
            'BOOTSTRAP_CONTROLLER_EXISTING_AUTHORITY_SCHEMA_REQUIRED')
        if catalog(bundle) is not None:
            require('META-INF/eatwhat-controller-catalog-v2' in jar.namelist()
                and jar.read('META-INF/eatwhat-controller-catalog-v2')==b'eatwhat-controller-catalog-v2\n'
                and 'BOOT-INF/classes/you/v50to/eatwhat/control/ControlBootstrapControllerCatalog.class' in jar.namelist(),
                'BOOTSTRAP_CONTROLLER_ARTIFACT_CATALOG_CAPABILITY_REQUIRED')
            if bundle['catalog']['version']==3:
                require('META-INF/eatwhat-controller-catalog-v3' in jar.namelist()
                    and jar.read('META-INF/eatwhat-controller-catalog-v3')==b'eatwhat-controller-catalog-v3\n',
                    'BOOTSTRAP_CONTROLLER_ARTIFACT_CATALOG_CAPABILITY_REQUIRED')
        if bundle.get('properties',{}).get('app.control.servers.ssh-routes-file','').endswith('/bootstrap-target-ssh-routes-v1.json'):
            require('META-INF/eatwhat-controller-target-factory-v1' in jar.namelist()
                and jar.read('META-INF/eatwhat-controller-target-factory-v1')==b'eatwhat-controller-target-factory-v1\n'
                and 'BOOT-INF/classes/you/v50to/eatwhat/control/ControlBootstrapControllerFactory.class' in jar.namelist(),
                'BOOTSTRAP_CONTROLLER_TARGET_FACTORY_CAPABILITY_REQUIRED')

def ui_files(path,bundle):
    require(path.stat().st_size==bundle['uiSize'] and digest(path.read_bytes())==bundle['uiSha256'],'BOOTSTRAP_CONTROLLER_UI_ARTIFACT_CHANGED')
    expected=bundle['uiFiles'];files={};seen=set();total=0
    directories={str(p) for name in expected for p in PurePosixPath(name).parents if str(p)!='.'}
    with tarfile.open(path,'r:gz') as archive:
        for item in archive:
            name=resource_name(item.name);require(name not in seen,'BOOTSTRAP_CONTROLLER_UI_DUPLICATE');seen.add(name)
            if item.isdir():require(name in directories);continue
            require(item.isfile() and name in expected and 0<=item.size<=4*1024*1024,'BOOTSTRAP_CONTROLLER_UI_ENTRY_INVALID')
            total+=item.size;require(total<=MAX_UI_CONTENT,'BOOTSTRAP_CONTROLLER_UI_TOO_LARGE')
            raw=archive.extractfile(item).read(item.size+1);require(len(raw)==item.size and digest(raw)==expected[name],
                'BOOTSTRAP_CONTROLLER_UI_FILE_CHANGED');files[name]=raw
    require(files.keys()==expected.keys(),'BOOTSTRAP_CONTROLLER_UI_INCOMPLETE');return files

def service_unit(bundle,dependency_id,mount_unit):
    identifier(dependency_id);require(re.fullmatch(r'[a-zA-Z0-9_.\\-]+\.mount',mount_unit))
    dependency='eatwhat-dependency-target-'+dependency_id+'.service'
    jar=CONTROL/'acceptance'/(sha(bundle['controllerSha256'])+'.jar')
    return ('''[Unit]
Description=EatWhat shared-state management controller
Requires='''+dependency+' '+mount_unit+'''
After=network-online.target '''+dependency+' '+mount_unit+'''
BindsTo='''+mount_unit+''' wg-quick@ewctrl0.service
[Service]
Type=exec
User=eatwhat-control
Group=eatwhat-control
WorkingDirectory=/opt/eatwhat-control
ExecStart=/usr/bin/java -Xms128m -Xmx768m -jar '''+str(jar)+''' --spring.config.location=file:/opt/eatwhat-control/config/application.properties --spring.config.additional-location= --spring.config.import=
Restart=on-failure
RestartSec=10
TimeoutStartSec=45
TimeoutStopSec=45
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/opt/eatwhat-control/state /opt/eatwhat-control/config
MemoryMax=1536M
UnsetEnvironment=JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH SPRING_APPLICATION_JSON SPRING_CONFIG_LOCATION SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_CONFIG_IMPORT
[Install]
WantedBy=multi-user.target
''').encode()
