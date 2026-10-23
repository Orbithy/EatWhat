package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;

/** Catalog-backed JAR/UI/business acceptance. Uses the same bundled pure policy
 * as the target, locally, before any remote effect. Never extracts an archive,
 * runs its code, accepts an HTTP path, or returns a local path to the browser.
 */
public final class ControlBootstrapArtifacts {
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ControlProperties settings;private final ControlServerStore store;
    public ControlBootstrapArtifacts(ControlProperties settings,ControlServerStore store){this.settings=settings;this.store=store;}
    public record File(String path,String sha256,long size) {
        @Override public String toString(){return "BootstrapArtifact[redacted]";}
    }
    public record Release(File artifact,String acceptanceEvidenceId,boolean rollbackCompatible) {}
    public record Frozen(File controller,File ui,File manifest,Map<String,String> uiFiles,String acceptanceEvidenceId,
                         String releaseId,File business,String businessAcceptanceEvidenceId,Map<String,String> images,File imageArchive,
                         Map<String,Release> releases,String binding) {
        public Frozen {uiFiles=Map.copyOf(uiFiles);images=Map.copyOf(images);releases=releases==null?Map.of(releaseId,new Release(business,businessAcceptanceEvidenceId,false)):Map.copyOf(releases);}
        /** Previously frozen single-release inputs keep their exact V2 shape. */
        public Frozen(File controller,File ui,File manifest,Map<String,String> uiFiles,String acceptanceEvidenceId,
                      String releaseId,File business,String businessAcceptanceEvidenceId,Map<String,String> images,File imageArchive,String binding){
            this(controller,ui,manifest,uiFiles,acceptanceEvidenceId,releaseId,business,businessAcceptanceEvidenceId,images,imageArchive,
                    Map.of(releaseId,new Release(business,businessAcceptanceEvidenceId,false)),binding);
        }
        @Override public String toString(){return "BootstrapArtifacts[redacted]";}
        public Map<String,Object> review(){var result=new LinkedHashMap<String,Object>(Map.of("controllerSha256",controller.sha256(),"controllerBytes",controller.size(),
                "uiSha256",ui.sha256(),"uiBytes",ui.size(),"uiFileCount",uiFiles.size(),"acceptanceEvidenceId",acceptanceEvidenceId,
                "releaseId",releaseId,"businessSha256",business.sha256(),"businessAcceptanceEvidenceId",businessAcceptanceEvidenceId));
            var catalog=new TreeMap<String,Object>();releases.forEach((id,entry)->catalog.put(id,Map.of("sha256",entry.artifact().sha256(),"bytes",entry.artifact().size(),
                    "acceptanceEvidenceId",entry.acceptanceEvidenceId(),"rollbackCompatible",entry.rollbackCompatible())));result.put("releases",catalog);return result;}
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    private static void hash(String value){require(value!=null&&value.matches("[a-f0-9]{64}"),"BOOTSTRAP_ARTIFACT_CATALOG_REQUIRED");}
    private static File file(Path path,long maximum){
        try {
            require(path!=null&&path.isAbsolute()&&path.equals(path.normalize()),"BOOTSTRAP_ARTIFACT_CATALOG_REQUIRED");
            for(Path p=path;p!=null;p=p.getParent())require(!Files.isSymbolicLink(p),"BOOTSTRAP_ARTIFACT_CATALOG_REQUIRED");
            require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)>0&&Files.size(path)<=maximum,
                    "BOOTSTRAP_ARTIFACT_CATALOG_REQUIRED");
            require(Files.getPosixFilePermissions(path).stream().noneMatch(p->p==java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
                    ||p==java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE),"BOOTSTRAP_ARTIFACT_CATALOG_REQUIRED");
            return new File(path.toString(),DeploymentExecutor.artifactSha(path),Files.size(path));
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_ARTIFACT_UNAVAILABLE");}
    }
    private static File file(Path path,String expected,long maximum){
        hash(expected);var observed=file(path,maximum);require(expected.equals(observed.sha256()),"BOOTSTRAP_ARTIFACT_CHANGED");return observed;
    }
    private static String evidence(String value){require(value!=null&&!value.isBlank()&&value.length()<=128
            &&value.matches("[A-Za-z0-9_.:/-]+"),"BOOTSTRAP_ARTIFACT_ACCEPTANCE_REQUIRED");return value;}
    private Map<String,String> images(){
        var s=settings.getServers();var result=Map.of("jre",s.getJreImage(),"postgres",s.getPostgresImage(),"redis",s.getRedisImage());
        result.values().forEach(v->require(v!=null&&v.matches("[A-Za-z0-9][A-Za-z0-9./:@_-]{0,199}"),"BOOTSTRAP_IMAGE_CATALOG_REQUIRED"));return result;
    }
    private String binding(Object value){try{return store.fingerprint(JSON.writeValueAsString(value));}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_ARTIFACT_UNAVAILABLE");}}
    private String bind(Frozen f){
        var values=new ArrayList<Object>(List.of(f.controller(),f.ui(),f.manifest(),new TreeMap<>(f.uiFiles()),f.acceptanceEvidenceId(),
                f.releaseId(),f.business(),f.businessAcceptanceEvidenceId(),new TreeMap<>(f.images()),f.imageArchive()==null?"":f.imageArchive()));
        if(ControlBootstrapControllerCatalog.multiple(f))values.add(new TreeMap<>(f.releases()));return binding(values);
    }
    public Frozen freeze(String releaseId){
        ControlService.identifier(releaseId);var c=settings.getControllerBootstrap();var r=settings.getReleases().get(releaseId);
        require(r!=null,"RELEASE_NOT_CONFIGURED");String acceptance=evidence(c.getAcceptanceEvidenceId()),businessAcceptance=evidence(r.getAcceptanceEvidenceId());
        File controller=file(c.getControllerArtifact(),c.getControllerSha256(),256L*1024*1024),ui=file(c.getUiArchive(),c.getUiSha256(),16L*1024*1024);
        File manifest;
        Map<String,String> files;
        try {
            Path path=c.getUiManifest();require(path!=null,"BOOTSTRAP_ARTIFACT_CATALOG_REQUIRED");
            manifest=file(path,65536);
            var value=JSON.readTree(Files.readAllBytes(path));require(value.path("sha256").asText().equals(ui.sha256())&&value.path("files").isObject(),"BOOTSTRAP_UI_MANIFEST_INVALID");
            var entries=value.path("files");require(entries.size()>=5&&entries.size()<=256,"BOOTSTRAP_UI_MANIFEST_INVALID");
            files=new TreeMap<>();for(String name:entries.propertyNames()){
                require(name.length()<=180&&name.matches("[A-Za-z0-9_./-]+")&&!name.startsWith("/")&&!name.contains("//")
                        &&Arrays.stream(name.split("/",-1)).noneMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")),"BOOTSTRAP_UI_MANIFEST_INVALID");
                require(entries.path(name).isString(),"BOOTSTRAP_UI_MANIFEST_INVALID");String digest=entries.path(name).asText();hash(digest);files.put(name,digest);
            }
            require(files.keySet().containsAll(Set.of("index.html","api.js","vendor/opencc/t2cn.js","vendor/opencc/LICENSE","vendor/opencc/THIRD_PARTY_LICENSES.md")),"BOOTSTRAP_UI_MANIFEST_INVALID");
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(409,"BOOTSTRAP_UI_MANIFEST_INVALID");}
        require(settings.getReleases().size()<=64,"BOOTSTRAP_RELEASE_CATALOG_TOO_LARGE");
        var releases=new TreeMap<String,Release>();
        settings.getReleases().forEach((id,release)->{
            ControlService.identifier(id);require(release!=null,"BOOTSTRAP_ARTIFACT_CATALOG_REQUIRED");
            releases.put(id,new Release(file(release.getArtifact(),release.getSha256(),256L*1024*1024),
                    evidence(release.getAcceptanceEvidenceId()),release.isRollbackCompatible()));
        });
        File business=releases.get(releaseId).artifact();
        var s=settings.getServers();File archive=s.getImageArchive()==null?null:file(s.getImageArchive(),s.getImageArchiveSha256(),2147483648L);
        var frozen=new Frozen(controller,ui,manifest,files,acceptance,releaseId,business,businessAcceptance,images(),archive,releases,"");
        validateContainers(frozen);
        return new Frozen(controller,ui,manifest,files,acceptance,releaseId,business,businessAcceptance,frozen.images(),archive,releases,bind(frozen));
    }
    public void requireUnchanged(Frozen frozen){
        require(frozen!=null&&frozen.binding().equals(bind(frozen)),"BOOTSTRAP_ARTIFACT_CHANGED");
        try{var current=freeze(frozen.releaseId());require(frozen.equals(current),"BOOTSTRAP_ARTIFACT_CHANGED");}
        catch(ControlFailure failure){if("BOOTSTRAP_ARTIFACT_UNAVAILABLE".equals(failure.code))throw failure;throw new ControlFailure(409,"BOOTSTRAP_ARTIFACT_CHANGED");}
    }
    /** SOURCE can be a different controller process with different local paths.
     * Every release it advertises must nevertheless survive in the frozen local
     * catalog with exactly the same bytes, acceptance and rollback decision.
     * Extra manager releases (including a newly selected release) are allowed.
     */
    static void requireSourceCatalog(Frozen frozen,Map<String,String> source){
        var releases=new TreeMap<String,Map<String,String>>();
        var pattern=java.util.regex.Pattern.compile("app\\.control\\.releases(?:\\[([A-Za-z0-9_-]{1,64})\\]|\\.([A-Za-z0-9_-]{1,64}))\\.(artifact|sha256|acceptance-evidence-id|rollback-compatible)");
        source.forEach((key,value)->{
            if(!key.startsWith("app.control.releases"))return;
            var match=pattern.matcher(key);require(match.matches(),"BOOTSTRAP_SOURCE_RELEASE_CATALOG_INVALID");
            String id=match.group(1)==null?match.group(2):match.group(1);var fields=releases.computeIfAbsent(id,ignored->new TreeMap<>());
            require(fields.putIfAbsent(match.group(3),value)==null,"BOOTSTRAP_SOURCE_RELEASE_CATALOG_INVALID");
        });
        require(releases.size()<=64,"BOOTSTRAP_SOURCE_RELEASE_CATALOG_INVALID");
        releases.forEach((id,fields)->{
            require(fields.keySet().containsAll(Set.of("artifact","sha256","acceptance-evidence-id")),"BOOTSTRAP_SOURCE_RELEASE_CATALOG_INVALID");
            String rollback=fields.getOrDefault("rollback-compatible","false");require(Set.of("true","false").contains(rollback),"BOOTSTRAP_SOURCE_RELEASE_CATALOG_INVALID");
            var release=frozen.releases().get(id);require(release!=null&&release.artifact().sha256().equals(fields.get("sha256"))
                    &&release.acceptanceEvidenceId().equals(fields.get("acceptance-evidence-id"))&&release.rollbackCompatible()==Boolean.parseBoolean(rollback),
                    "BOOTSTRAP_SOURCE_RELEASE_CATALOG_CHANGED");
        });
    }
    private void validateContainers(Frozen f){
        try {
            String policy=Base64.getEncoder().encodeToString(ControlServers.resource("controller-bootstrap-controller-policy.py").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String program="import base64,json,pathlib,sys\nns={}\nexec(base64.b64decode('"+policy+"'),ns)\nh=json.load(sys.stdin)\n"+
                "try:\n ns['verify_jar'](pathlib.Path(h['controller']),h['bundle'])\n ns['ui_files'](pathlib.Path(h['ui']),h['bundle'])\n ns['verify_catalog_files']({k:pathlib.Path(v) for k,v in h['catalogPaths'].items()},h['bundle'])\n"+
                " import zipfile\n with zipfile.ZipFile(h['controller']) as jar:\n  assert jar.read('META-INF/eatwhat-controller-source-snapshot-v1')==b'eatwhat-controller-source-snapshot-v1\\n'\n"+
                "  assert 'BOOT-INF/classes/you/v50to/eatwhat/control/bootstrap/ControllerSourceSnapshot.class' in jar.namelist()\n"+
                "  assert jar.read('META-INF/eatwhat-controller-target-factory-v1')==b'eatwhat-controller-target-factory-v1\\n'\n"+
                "  assert 'BOOT-INF/classes/you/v50to/eatwhat/control/ControlBootstrapControllerFactory.class' in jar.namelist()\n"+
                "  assert jar.read('META-INF/eatwhat-controller-profile-receiver-v1')==b'eatwhat-controller-profile-receiver-v1\\n'\n"+
                "  assert all('BOOT-INF/classes/you/v50to/eatwhat/control/'+n+'.class' in jar.namelist() for n in ['ControlBootstrapProfileNodeController','ControlBootstrapProfileReceiver','ControlBootstrapProfileState','ControlBootstrapProfileServices'])\n"+
                "  assert jar.read('META-INF/eatwhat-controller-profile-services-successor-v1')==b'eatwhat-controller-profile-services-successor-v1\\n'\n"+
                "  assert jar.read('META-INF/eatwhat-controller-bootstrap-membership-v1')==b'eatwhat-controller-bootstrap-membership-v1\\n'\n"+
                "  assert jar.read('META-INF/eatwhat-controller-bootstrap-membership-v2')==b'eatwhat-controller-bootstrap-membership-v2\\n'\n"+
                "  assert 'BOOT-INF/classes/you/v50to/eatwhat/cluster/ClusterMembershipCoordinator$Boundary.class' in jar.namelist()\n"+
                "  assert jar.read('META-INF/eatwhat-controller-bootstrap-verify-v1')==b'eatwhat-controller-bootstrap-verify-v1\\n'\n"+
                "  assert all('BOOT-INF/classes/you/v50to/eatwhat/control/'+n+'.class' in jar.namelist() for n in ['ControlBootstrapVerify','ControlBootstrapMemberRuntime'])\n"+
                "  assert all('BOOT-INF/classes/you/v50to/eatwhat/control/'+n+'.class' in jar.namelist() for n in ['ControlBootstrapMembership','ControlBootstrapMembershipNodeController'])\n print(json.dumps({'valid':True}))\nexcept Exception as failure:\n print(json.dumps({'valid':False,'unavailable':isinstance(failure,OSError)}))\n";
            var bundle=Map.<String,Object>of("controllerSha256",f.controller().sha256(),"controllerSize",f.controller().size(),"uiSha256",f.ui().sha256(),"uiSize",f.ui().size(),"uiFiles",f.uiFiles(),
                    "catalog",ControlBootstrapControllerCatalog.metadata(f));
            var paths=new TreeMap<String,String>();ControlBootstrapControllerCatalog.files(f,Map.of("bundle",bundle)).forEach((kind,file)->paths.put(kind,file.path()));
            byte[] input=JSON.writeValueAsBytes(Map.of("controller",f.controller().path(),"ui",f.ui().path(),"bundle",bundle,"catalogPaths",paths));
            byte[] output=ControlSsh.process(List.of("/usr/bin/python3","-I","-c",program),Map.of(),out->out.write(input),30,1024,true);
            var result=JSON.readValue(output,Map.class);require(!Boolean.TRUE.equals(result.get("unavailable")),"BOOTSTRAP_ARTIFACT_UNAVAILABLE");
            require(Boolean.TRUE.equals(result.get("valid")),"BOOTSTRAP_ARTIFACT_PROTOCOL_OR_UI_INVALID");
        }catch(ControlFailure failure){throw failure;}catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_ARTIFACT_UNAVAILABLE");}
    }
}
