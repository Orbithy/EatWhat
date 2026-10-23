package you.v50to.eatwhat.control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import java.net.URI;

/** Optional entry for an approved external static bundle served by Spring MVC.
 * No UI assets or management authentication policy are changed here.
 */
@Controller
@ConditionalOnProperty(name={"app.control.enabled","app.control.ui-enabled"},havingValue="true")
public class ControlUiController {
    @GetMapping({"/control-ui","/control-ui/"})
    public ResponseEntity<Void> index(){
        // Keep the browser's public scheme/host when TLS terminates at the
        // operator's reverse proxy and this listener is loopback HTTP.
        return ResponseEntity.status(302).location(URI.create("/control-ui/index.html")).build();
    }
}
