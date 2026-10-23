package you.v50to.eatwhat.cluster;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.control.ControlNode;
import java.util.*;

@RestController @RequestMapping("/internal/cluster/v1")
@ConditionalOnProperty(name={"app.cluster.enabled","app.storage.files.replication.enabled"},havingValue="true")
public class ClusterFileController {
    private final ControlNode auth;private final ClusterNodeAgent agent;private final ClusterFileTransfer files;
    public ClusterFileController(ControlNode auth,ClusterNodeAgent agent,ClusterFileTransfer files){this.auth=auth;this.agent=agent;this.files=files;}
    private Object result(String key,Object value){var map=new LinkedHashMap<>(agent.probe());map.put(key,value);return map;}
    @PostMapping("/file-proof") public Object proof(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody ClusterFileTransfer.Revision file){auth.authenticate(token);return result("proof",files.proof(file));}
    @PostMapping("/file-manifest") public Object manifest(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@RequestBody ClusterFileTransfer.Manifest manifest){auth.authenticate(token);files.manifest(manifest);return result("accepted",true);}
    @GetMapping("/files/{id}/{revision}") public void download(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@PathVariable UUID id,@PathVariable int revision,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{auth.authenticate(token);response.setHeader("Cache-Control","no-store");response.setContentType("application/octet-stream");files.download(new ClusterFileTransfer.Revision(id,revision),response.getOutputStream());}
    @PutMapping("/files/{id}/{revision}") public Object upload(@RequestHeader(value="X-Control-Node-Token",required=false) String token,@PathVariable UUID id,@PathVariable int revision,jakarta.servlet.http.HttpServletRequest request)throws java.io.IOException{auth.authenticate(token);return result("proof",files.upload(new ClusterFileTransfer.Revision(id,revision),request.getInputStream()));}
}
