package you.v50to.eatwhat.cluster;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import you.v50to.eatwhat.control.ControlNode;
import java.io.*;

/** Authenticate before JSON allocation; bound chunked bodies too. Binary files have their immutable metadata bound. */
@Component @Order(-2100) @ConditionalOnProperty(name="app.cluster.enabled",havingValue="true")
public class ClusterRequestGuard extends OncePerRequestFilter {
    private final ControlNode authentication;public ClusterRequestGuard(ControlNode authentication){this.authentication=authentication;}
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String path=request.getServletPath();if(!path.startsWith("/internal/cluster/v1/")){chain.doFilter(request,response);return;}
        if(path.equals("/internal/cluster/v1/account")){chain.doFilter(request,response);return;}
        try{authentication.authenticate(request.getHeader("X-Control-Node-Token"));}catch(you.v50to.eatwhat.control.ControlFailure failure){response.setStatus(failure.status);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"code\":\"NODE_AUTH_REQUIRED\"}");return;}
        if(path.startsWith("/internal/cluster/v1/files/")){chain.doFilter(request,response);return;}
        int limit=4*1024*1024+65536;if(request.getContentLengthLong()>limit){response.setStatus(413);return;}
        var wrapper=new HttpServletRequestWrapper(request){
            private ServletInputStream bounded;
            @Override public ServletInputStream getInputStream()throws IOException{if(bounded==null){var input=super.getInputStream();bounded=new ServletInputStream(){int count;public boolean isFinished(){return input.isFinished();}public boolean isReady(){return input.isReady();}public void setReadListener(ReadListener listener){input.setReadListener(listener);}public int read()throws IOException{int value=input.read();if(value>=0&&++count>limit)throw new IOException("Cluster body exceeds limit");return value;}public int read(byte[] bytes,int offset,int length)throws IOException{int n=input.read(bytes,offset,Math.min(length,limit-count+1));if(n>0&&(count+=n)>limit)throw new IOException("Cluster body exceeds limit");return n;}};}return bounded;}
        };chain.doFilter(wrapper,response);
    }
}
