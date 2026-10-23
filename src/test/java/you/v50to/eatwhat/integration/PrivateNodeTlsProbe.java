package you.v50to.eatwhat.integration;

import you.v50to.eatwhat.config.NodeHttpClients;
import javax.net.ssl.SSLHandshakeException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;

/** Explicit read-only real-host acceptance. Sends no node/admin token and changes no trust defaults. */
public final class PrivateNodeTlsProbe {
 public static void main(String[] args)throws Exception{
  if(args.length!=1&&args.length!=2)throw new IllegalArgumentException();
  String origin=args.length==2?args[1]:"https://10.253.74.1:19443";
  if(!java.util.Set.of("https://10.253.74.1:19443","https://10.253.74.2:19443").contains(origin))throw new IllegalArgumentException();
  var client=NodeHttpClients.create(Path.of(args[0]));
  var health=HttpRequest.newBuilder(URI.create(origin+"/actuator/health/readiness")).timeout(Duration.ofSeconds(8)).GET().build();
  if(client.send(health,HttpResponse.BodyHandlers.discarding()).statusCode()!=200)throw new IllegalStateException("PRIVATE_HEALTH_FAILED");
  // The control node exists before cluster activation; a cluster/probe 404 on an
  // unjoined installation is not evidence of a failed authentication boundary.
  var probe=HttpRequest.newBuilder(URI.create(origin+"/internal/control/v1/snapshot")).timeout(Duration.ofSeconds(8)).GET().build();
  if(client.send(probe,HttpResponse.BodyHandlers.discarding()).statusCode()!=401)throw new IllegalStateException("NODE_TOKEN_BOUNDARY_FAILED");
  try{NodeHttpClients.create(null).send(health,HttpResponse.BodyHandlers.discarding());throw new IllegalStateException("DEFAULT_TRUST_CHANGED");}catch(SSLHandshakeException expected){}
  System.out.println("{\"status\":\"PASS_REAL_JAVA_PRIVATE_CA_NO_TOKEN\",\"readiness\":200,\"anonymousControlSnapshot\":401,\"unconfiguredClientRejectsCa\":true,\"globalTrustModified\":false}");
 }
}
