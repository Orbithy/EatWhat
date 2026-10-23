package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlFaultProxyTest {
    @Test void actualTcpForwardingBlockClosesExistingConnectionsAndExpires()throws Exception {
        try(var target=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            workers.submit(()->{try{while(!target.isClosed()){var s=target.accept();workers.submit(()->{try(s){int b;while((b=s.getInputStream().read())!=-1)s.getOutputStream().write(b);}catch(Exception ignored){}});}}catch(Exception ignored){}});
            int port;try(var free=new ServerSocket(0)){port=free.getLocalPort();}
            var p=new ControlProperties.Proxy();p.setListenPort(port);p.setTargetHost("127.0.0.1");p.setTargetPort(target.getLocalPort());p.setMaximumBlockSeconds(2);
            var expired=new java.util.concurrent.atomic.AtomicBoolean();
            try(var proxy=new FaultProxy(p,()->expired.set(true));var existing=new Socket("127.0.0.1",port)) {
                existing.setSoTimeout(2000);existing.getOutputStream().write(42);assertEquals(42,existing.getInputStream().read());
                proxy.block(1);assertEquals("BLOCKED",proxy.state().get("state"));assertEquals(-1,existing.getInputStream().read());
                try(var blocked=new Socket("127.0.0.1",port)){blocked.setSoTimeout(2000);assertEquals(-1,blocked.getInputStream().read());}
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(proxy.state().get("state").equals("BLOCKED")&&System.nanoTime()<deadline)Thread.sleep(30);
                assertEquals("FORWARDING",proxy.state().get("state"));assertTrue(expired.get());assertFalse((Boolean)proxy.state().get("routingVerified"));
                try(var restored=new Socket("127.0.0.1",port)){restored.setSoTimeout(2000);restored.getOutputStream().write(7);assertEquals(7,restored.getInputStream().read());}
                assertThrows(ControlFailure.class,()->proxy.block(3));assertTrue((Long)proxy.state().get("forwardedBytes")>=4);
            } finally{target.close();workers.shutdownNow();}
        }
    }
    @Test void cannotProxyToItselfOrBindPrivilegedPort(){var p=new ControlProperties.Proxy();p.setListenPort(12);p.setTargetHost("127.0.0.1");p.setTargetPort(80);assertThrows(ControlFailure.class,()->new FaultProxy(p));p.setListenPort(12001);p.setTargetPort(12001);assertThrows(ControlFailure.class,()->new FaultProxy(p));}
}
