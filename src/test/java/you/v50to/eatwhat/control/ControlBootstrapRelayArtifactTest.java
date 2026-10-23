package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ControlBootstrapRelayArtifactTest {
    @TempDir Path root;
    @Test void clientProbeRequiresOriginalNetworkAndUnchangedOwnedAppProcess()throws Exception{
        Path source=Path.of(getClass().getResource("/control/controller-bootstrap-member-relay.py").toURI());
        String program="""
            import pathlib,sys,types,uuid
            m=types.ModuleType('relay_client_fixture');exec(compile(pathlib.Path(sys.argv[1]).read_bytes(),'<reviewed-relay>','exec'),m.__dict__)
            data={'serverId':str(uuid.uuid4()),'businessId':str(uuid.uuid4())};network='a'*64;state={'config':{'networkId':network}}
            proc={'pid':1234,'startTicks':'5678','netnsInode':9012}
            class Network:
                SERVER_LABEL='server';JOB_LABEL='job'
                def docker_networks(self):return {network:own}
                def container_observation(self,cid,name,q):
                    assert q=={'serverId':data['serverId'],'jobId':data['businessId']}
                    assert name=='eatwhat-site-'+data['serverId'][:8]+'_default'
                    return {'Labels':{'com.docker.compose.service':cid},'process':dict(proc)}
                def namespace_identity(self,pid):assert pid==1234;return dict(current)
            n=Network();b=types.SimpleNamespace(network_policy=lambda:n);m.original_business=lambda d,f:(b,{},{});current=dict(proc)
            own={'Id':network,'Name':'eatwhat-site-'+data['serverId'][:8]+'_default','Labels':{'server':data['serverId'],'job':data['businessId']},'Containers':['app','postgres','redis']}
            assert m.probe_process(data,state)==(n,proc)
            for mutation,restore in [(lambda:own['Labels'].update(job=str(uuid.uuid4())),lambda:own['Labels'].update(job=data['businessId'])),
                (lambda:own.update(Containers=['app','app','redis']),lambda:own.update(Containers=['app','postgres','redis'])),
                (lambda:current.update(startTicks='9999'),lambda:current.update(proc))]:
                mutation()
                try:m.probe_process(data,state)
                except m.Refusal as e:assert e.code=='BOOTSTRAP_MEMBER_RELAY_NETWORK_CHANGED'
                else:raise AssertionError('unproved namespace accepted')
                restore()
            print('OWNED_APP_NAMESPACE_REQUIRED')
            """;
        var p=new ProcessBuilder("python3","-I","-B","-c",program,source.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertTrue(p.waitFor(15,TimeUnit.SECONDS));assertEquals(0,p.exitValue());assertTrue(new String(p.getInputStream().readAllBytes()).contains("OWNED_APP_NAMESPACE_REQUIRED"));
    }
    @Test void serviceExecutableIsAnExactPrivateCopyAndNeverOverwritesRetainedFiles()throws Exception{
        Path source=Path.of(getClass().getResource("/control/controller-bootstrap-member-relay.py").toURI());
        String program="""
            import hashlib,json,os,pathlib,stat,sys,types,uuid
            m=types.ModuleType('relay_artifact_fixture');exec(compile(pathlib.Path(sys.argv[1]).read_bytes(),'<reviewed-relay>','exec'),m.__dict__)
            root=pathlib.Path(sys.argv[2]);m.ROOT_UID=os.getuid();m.ROOT_GID=os.getgid();m.SITES=root/'sites';m.SITES.mkdir(mode=0o700)
            data={'id':str(uuid.uuid4()),'serverId':str(uuid.uuid4())};parent=m.SITES/data['serverId']/'private';parent.mkdir(parents=True);os.chmod(parent,0o700)
            path=m.folder(data);path.mkdir(mode=0o700);artifact=root/'private-controller-public-artifact.jar';raw=b'public executable fixture bytes'
            artifact.write_bytes(raw);artifact.chmod(0o600)
            state={'artifactSource':str(artifact),'controllerSize':len(raw),'controllerSha256':hashlib.sha256(raw).hexdigest(),'jar':str(path/'runtime.jar'),'config':{'clientSubnet':'172.30.0.0/24'}}
            m.stage_runtime_artifact(data,state)
            assert m.private(path/'runtime.jar',256*1024*1024)==raw and stat.S_IMODE((path/'runtime.jar').stat().st_mode)==0o600
            assert artifact.read_bytes()==raw and stat.S_IMODE(artifact.stat().st_mode)==0o600
            unit=m.rendered_unit(data,state)
            assert b'CapabilityBoundingSet=\\n' in unit and b'NoNewPrivileges=yes\\n' in unit and str(path/'runtime.jar').encode() in unit
            try:m.stage_runtime_artifact(data,state)
            except m.Refusal as e:assert e.code=='BOOTSTRAP_MEMBER_RELAY_RETAINED'
            else:raise AssertionError('retained executable overwritten')
            (path/'runtime.jar').unlink();artifact.write_bytes(b'changed public artifact')
            try:m.stage_runtime_artifact(data,state)
            except m.Refusal as e:assert e.code=='BOOTSTRAP_MEMBER_RELAY_ARTIFACT_CHANGED'
            else:raise AssertionError('changed executable accepted')
            assert not (path/'runtime.jar').exists()
            print(json.dumps({'exactCopy':True,'controllerPermissionsPreserved':True,'serviceCapabilitiesStillEmpty':True,'retainedAndChangedArtifactsRejected':True}))
            """;
        var p=new ProcessBuilder("python3","-I","-B","-c",program,source.toString(),root.toRealPath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertTrue(p.waitFor(15,TimeUnit.SECONDS));assertEquals(0,p.exitValue());assertTrue(new String(p.getInputStream().readAllBytes()).contains("retainedAndChangedArtifactsRejected"));
    }
}
