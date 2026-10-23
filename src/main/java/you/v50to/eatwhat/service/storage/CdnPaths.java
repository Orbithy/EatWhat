package you.v50to.eatwhat.service.storage;

import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * The one place that encodes an object key into a public CDN path, shared by {@link S3CompatibleStorageService}
 * (which prefixes the public base URL) and the CDN invalidation worker (which needs only the host-less path).
 */
public final class CdnPaths {
    private CdnPaths() {
    }

    /** True when a key contains "." or ".." segments that browsers/proxies would normalize to another object. */
    public static boolean hasDotSegment(String key) {
        for (String segment : key.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    /** The key as it appears in a public URL path (same encoding the public read URLs use). */
    public static String encodeKey(String key) {
        return UriUtils.encodePath(key, StandardCharsets.UTF_8);
    }

    /**
     * Host-less, query-less CDN path of a key: the path component of the public base URL, then the encoded key.
     * Never contains a scheme, host, query or signature. Returns null when no usable public base URL is
     * configured or the key has dot segments.
     */
    public static String relativePath(String publicBaseUrl, String key) {
        if (!StringUtils.hasText(publicBaseUrl) || !StringUtils.hasText(key) || hasDotSegment(key)) {
            return null;
        }
        String basePath;
        try {
            basePath = URI.create(publicBaseUrl.trim()).getRawPath();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        String normalizedBase = basePath == null ? "" : basePath.replaceAll("/+$", "");
        return normalizedBase + "/" + encodeKey(key);
    }
}
