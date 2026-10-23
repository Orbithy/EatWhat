package you.v50to.eatwhat.cluster;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.account.AccountUnavailableException;
import static org.junit.jupiter.api.Assertions.*;

class ClusterAccountProfileCompatibilityTest {
    public record Scoped(HttpClient scoped){public HttpClient client(HttpClient fallback){return scoped;}}
    public static class Broken {public HttpClient client(HttpClient fallback){throw new IllegalStateException("synthetic TLS failure");}}
    public static class NullClient {public HttpClient client(HttpClient fallback){return null;}}
    @Test void legacyProfileKeepsExistingClient(){var client=HttpClient.newHttpClient();assertSame(client,ClusterAccountChannel.profileClient(new Object(),client));}
    @Test void availableScopedTrustIsUsed(){var fallback=HttpClient.newHttpClient();var scoped=HttpClient.newHttpClient();assertSame(scoped,ClusterAccountChannel.profileClient(new Scoped(scoped),fallback));}
    @Test void brokenScopedTrustNeverFallsBack(){var fallback=HttpClient.newHttpClient();assertThrows(AccountUnavailableException.class,()->ClusterAccountChannel.profileClient(new Broken(),fallback));assertThrows(AccountUnavailableException.class,()->ClusterAccountChannel.profileClient(new NullClient(),fallback));}
}
