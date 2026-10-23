package you.v50to.eatwhat.cluster;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(basePackageClasses=ClusterAdminController.class)
@org.springframework.core.annotation.Order(-1100)
@ConditionalOnProperty(name="app.cluster.enabled",havingValue="true")
public class ClusterExceptionHandler {
    @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class) public ResponseEntity<?> login(){return response(401,"AUTHENTICATION_REQUIRED");}
    @ExceptionHandler(cn.dev33.satoken.exception.NotRoleException.class) public ResponseEntity<?> role(){return response(403,"ADMIN_REQUIRED");}
    @ExceptionHandler(you.v50to.eatwhat.control.ControlFailure.class) public ResponseEntity<?> control(you.v50to.eatwhat.control.ControlFailure failure){return response(failure.status,failure.code);}
    @ExceptionHandler(ClusterFailure.class) public ResponseEntity<?> cluster(ClusterFailure failure){return response(failure.status(),failure.getMessage());}
    @ExceptionHandler(you.v50to.eatwhat.account.ConsentStore.ConsentException.class) public ResponseEntity<?> consent(you.v50to.eatwhat.account.ConsentStore.ConsentException failure){return response(409,failure.code());}
    @ExceptionHandler({org.springframework.dao.DataAccessException.class}) public ResponseEntity<?> storage(){return response(503,"CLUSTER_STORAGE_UNAVAILABLE");}
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,IllegalArgumentException.class}) public ResponseEntity<?> invalid(){return response(400,"INVALID_CLUSTER_REQUEST");}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(){return response(500,"CLUSTER_REQUEST_FAILED");}
    private ResponseEntity<?> response(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));}
}
