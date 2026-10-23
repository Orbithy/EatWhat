package you.v50to.eatwhat.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable bounded causal stream names that rotate with consent epochs. */
public final class EpochDotSite {
    private EpochDotSite() { }

    public static String of(String domain, String originSite, long epoch) {
        if (epoch < 0) throw new IllegalArgumentException("Invalid consent epoch");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((domain + "\u0000" + originSite + "\u0000" + epoch).getBytes(StandardCharsets.UTF_8));
            return domain.substring(0, 1) + "-" + HexFormat.of().formatHex(digest, 0, 24);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
