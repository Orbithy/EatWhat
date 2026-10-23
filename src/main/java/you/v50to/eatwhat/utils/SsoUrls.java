package you.v50to.eatwhat.utils;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 拼统一认证地址。app.oauth-url 形如 https://cas.example/login/page?forward=<回跳地址>。 */
public final class SsoUrls {
    private SsoUrls() { }

    /**
     * 回跳地址的前缀：配置了 app.oauth-callback-url 就用它；否则取 oauth-url 里 forward 的协议和主机，路径用 /api/callback
     * （网关去掉 /api/ 后正好是后端的 /callback）。
     */
    public static String callbackBase(String oauthUrl, String configuredCallback) {
        if (configuredCallback != null && !configuredCallback.isBlank()) return stripTrailingSlash(configuredCallback.trim());
        String forward = forward(oauthUrl);
        if (forward == null) throw new IllegalStateException("app.oauth-url 缺少 forward 参数，且没有配置 app.oauth-callback-url");
        URI uri = URI.create(forward);
        return uri.getScheme() + "://" + uri.getRawAuthority() + "/api/callback";
    }

    /** 把 oauth-url 的 forward 换成 callback；state 放在路径里，统一认证往回跳地址追加 ?token= 时不会和它冲突。 */
    public static String loginUrl(String oauthUrl, String callback) {
        int q = oauthUrl.indexOf('?');
        String base = q < 0 ? oauthUrl : oauthUrl.substring(0, q);
        List<String> params = new ArrayList<>();
        if (q >= 0) {
            for (String pair : oauthUrl.substring(q + 1).split("&")) {
                if (!pair.isEmpty() && !pair.startsWith("forward=")) params.add(pair);
            }
        }
        params.add("forward=" + URLEncoder.encode(callback, StandardCharsets.UTF_8));
        return base + "?" + String.join("&", params);
    }

    private static String forward(String oauthUrl) {
        int q = oauthUrl.indexOf('?');
        if (q < 0) return null;
        for (String pair : oauthUrl.substring(q + 1).split("&")) {
            if (pair.startsWith("forward=")) return URLDecoder.decode(pair.substring("forward=".length()), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
