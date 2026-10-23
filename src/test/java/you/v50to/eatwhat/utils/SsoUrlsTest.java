package you.v50to.eatwhat.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SsoUrlsTest {

    @Test
    void callbackBaseUsesTheForwardHostWithTheGatewayCallbackPath() {
        assertEquals("https://v50to.you/api/callback", SsoUrls.callbackBase(
                "https://i.sdu.edu.cn/cas/proxy/login/page?forward=https%3A%2F%2Fv50to.you%2Fapi%2Fv1%2Fcallback", ""));
        assertEquals("https://v50to.you/api/callback", SsoUrls.callbackBase(
                "https://i.sdu.edu.cn/cas/proxy/login/page?forward=https://v50to.you/api/v1/callback", null));
        assertEquals("http://127.0.0.1:8080/api/callback", SsoUrls.callbackBase(
                "https://cas.test/login?forward=http://127.0.0.1:8080/x", " "));
    }

    @Test
    void configuredCallbackWinsAndMissingForwardIsAConfigurationError() {
        assertEquals("https://other.test/cb", SsoUrls.callbackBase("https://cas.test/login", "https://other.test/cb/"));
        assertThrows(IllegalStateException.class, () -> SsoUrls.callbackBase("https://cas.test/login", ""));
    }

    @Test
    void loginUrlReplacesForwardAndKeepsOtherParameters() {
        assertEquals("https://cas.test/login?lang=zh&forward=https%3A%2F%2Fapp.test%2Fapi%2Fcallback%2Fabc",
                SsoUrls.loginUrl("https://cas.test/login?lang=zh&forward=https%3A%2F%2Fold", "https://app.test/api/callback/abc"));
        assertEquals("https://cas.test/login?forward=https%3A%2F%2Fapp.test%2Fcb",
                SsoUrls.loginUrl("https://cas.test/login", "https://app.test/cb"));
    }
}
