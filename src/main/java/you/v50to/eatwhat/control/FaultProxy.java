package you.v50to.eatwhat.control;

import java.net.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Byte-transparent loopback TCP proxy. Does not terminate TLS or touch host firewall/DNS. */
public final class FaultProxy implements AutoCloseable {
    private final ServerSocket listener;
    private final String host;
    private final int port,maximum;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"acceptance-fault-expiry");t.setDaemon(true);return t;});
    private final AtomicLong accepted=new AtomicLong(),forwarded=new AtomicLong();
    private long until;
    private boolean closed;
    private final Runnable onExpiry;
    public FaultProxy(ControlProperties.Proxy p) {
        this(p,()->{});
    }
    public FaultProxy(ControlProperties.Proxy p,Runnable onExpiry) {
        this.onExpiry=onExpiry;
        try {
            if(p.getListenPort()<1024 || p.getListenPort()>65535 || p.getTargetPort()<1 || p.getTargetPort()>65535
                    || p.getTargetHost()==null || !p.getTargetHost().matches("[A-Za-z0-9.-]{1,253}")
                    || p.getMaximumBlockSeconds()<1 || p.getMaximumBlockSeconds()>300) throw new Exception();
            host=p.getTargetHost();port=p.getTargetPort();maximum=p.getMaximumBlockSeconds();
            if((host.equals("127.0.0.1") || host.equals("localhost")) && port==p.getListenPort()) throw new Exception();
            listener=new ServerSocket();listener.bind(new InetSocketAddress("127.0.0.1",p.getListenPort()));
        } catch(Exception e) { throw new ControlFailure(503,"FAULT_PROXY_CONFIGURATION_INVALID"); }
        workers.submit(this::acceptLoop);
        timer.scheduleWithFixedDelay(this::expire,100,100,TimeUnit.MILLISECONDS);
    }
    public synchronized Map<String,Object> state() {
        return Map.of("state",until>0?"BLOCKED":"FORWARDING","expiresAt",until>0?Instant.ofEpochMilli(until).toString():"",
                "acceptedConnections",accepted.get(),"forwardedBytes",forwarded.get(),"routingVerified",false,"maximumBlockSeconds",maximum);
    }
    public synchronized void block(int seconds) {
        if(seconds<1 || seconds>maximum) throw new ControlFailure(400,"INVALID_FAULT_DURATION");
        until=System.currentTimeMillis()+seconds*1000L;
        sockets.forEach(FaultProxy::closeSocket);
    }
    public synchronized void restore() { until=0; }
    private synchronized void expire() { if(until>0 && System.currentTimeMillis()>=until) {restore();try{onExpiry.run();}catch(RuntimeException ignored){/* Never extend a fault because audit storage failed. */}} }
    private void acceptLoop() {
        while(!listener.isClosed()) {
            try { Socket s=listener.accept();accepted.incrementAndGet();workers.submit(()->forward(s)); }
            catch(IOException e) { if(listener.isClosed()) return; }
        }
    }
    private void forward(Socket downstream) {
        Socket upstream=new Socket();
        try {
            synchronized(this) { if(until>0 || closed) return; sockets.add(downstream);sockets.add(upstream); }
            upstream.connect(new InetSocketAddress(host,port),3000);
            synchronized(this) { if(until>0 || closed) return; }
            var reverse=workers.submit(()->copy(upstream,downstream));
            copy(downstream,upstream);reverse.get();
        } catch(Exception ignored) {
        } finally { sockets.remove(downstream);sockets.remove(upstream);closeSocket(downstream);closeSocket(upstream); }
    }
    private void copy(Socket from,Socket to) {
        try {
            byte[] b=new byte[16384];int n;
            while((n=from.getInputStream().read(b))!=-1) {to.getOutputStream().write(b,0,n);forwarded.addAndGet(n);}
            to.shutdownOutput();
        } catch(IOException e) {closeSocket(from);closeSocket(to);}
    }
    private static void closeSocket(Socket s) { try{s.close();}catch(IOException ignored){} }
    @Override public synchronized void close() {
        closed=true;restore();sockets.forEach(FaultProxy::closeSocket);
        try{listener.close();}catch(IOException ignored){}
        timer.shutdownNow();workers.shutdownNow();
    }
}
