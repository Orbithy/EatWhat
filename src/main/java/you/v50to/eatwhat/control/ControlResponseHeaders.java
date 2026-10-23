package you.v50to.eatwhat.control;

import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice(basePackageClasses=ControlController.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlResponseHeaders implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter p,Class<? extends HttpMessageConverter<?>> c){return true;}
    @Override public Object beforeBodyWrite(Object body,MethodParameter p,MediaType m,Class<? extends HttpMessageConverter<?>> c,ServerHttpRequest request,ServerHttpResponse response){
        response.getHeaders().setCacheControl("no-store");response.getHeaders().set("Pragma","no-cache");return body;
    }
}
