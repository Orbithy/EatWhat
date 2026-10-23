package you.v50to.eatwhat.control;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.time.Instant;
import java.util.Map;

@RestControllerAdvice(basePackageClasses=ControlController.class)
@Order(-1000)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.control.enabled",havingValue="true")
public class ControlExceptionHandler {
    private ResponseEntity<?> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("code",code,"timestamp",Instant.now().toString()));}
    @ExceptionHandler(ControlFailure.class) public ResponseEntity<?> failure(ControlFailure e){return error(e.status,e.code);}
    @ExceptionHandler(you.v50to.eatwhat.cluster.ClusterFailure.class) public ResponseEntity<?> cluster(you.v50to.eatwhat.cluster.ClusterFailure e){return error(e.status(),e.getMessage());}
    @ExceptionHandler(NotLoginException.class) public ResponseEntity<?> login(){return error(401,"AUTHENTICATION_REQUIRED");}
    @ExceptionHandler(NotRoleException.class) public ResponseEntity<?> role(){return error(403,"ADMIN_REQUIRED");}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> validation(){return error(400,"INVALID_REQUEST");}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(){return error(500,"CONTROL_REQUEST_FAILED");}
}
