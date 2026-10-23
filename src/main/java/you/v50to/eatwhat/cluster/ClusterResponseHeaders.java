package you.v50to.eatwhat.cluster;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice(basePackageClasses=ClusterAdminController.class)
@ConditionalOnProperty(name="app.cluster.enabled",havingValue="true")
public class ClusterResponseHeaders implements ResponseBodyAdvice<Object> {
    public boolean supports(MethodParameter p,Class<? extends HttpMessageConverter<?>> c){return true;}
    public Object beforeBodyWrite(Object body,MethodParameter p,MediaType m,Class<? extends HttpMessageConverter<?>> c,ServerHttpRequest request,ServerHttpResponse response){response.getHeaders().setCacheControl("no-store");response.getHeaders().set("Pragma","no-cache");return body;}
}
