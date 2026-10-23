package you.v50to.eatwhat.control.independent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.UUID;

/** Explicit opt-in management realm; never inferred from a copied business database. */
@Data
@ConfigurationProperties("app.control.independent")
public class IndependentControlProperties {
    private boolean enabled;
    private UUID controllerId;
    private String authoritySiteId;
    private UUID authorityInstanceId;
    private UUID installationId;
}
