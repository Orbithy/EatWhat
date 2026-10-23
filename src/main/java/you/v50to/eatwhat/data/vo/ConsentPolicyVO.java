package you.v50to.eatwhat.data.vo;

import you.v50to.eatwhat.account.BrowseConsentPolicy;
import you.v50to.eatwhat.account.PreferenceConsentPolicy;

/** Current consent version and its public document URL, relative to the site origin. */
public record ConsentPolicyVO(String version, String url) {
    public static ConsentPolicyVO of(String version) {
        String document = switch (version) {
            case PreferenceConsentPolicy.VERSION -> PreferenceConsentPolicy.DOCUMENT;
            case BrowseConsentPolicy.VERSION -> BrowseConsentPolicy.DOCUMENT;
            default -> null;
        };
        // Public business gateways strip /api before forwarding to the backend.
        return new ConsentPolicyVO(version, document == null ? null : "/api" + document);
    }
}
