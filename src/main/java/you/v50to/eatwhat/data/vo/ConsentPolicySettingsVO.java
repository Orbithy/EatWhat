package you.v50to.eatwhat.data.vo;

/** Revision is administrative concurrency metadata; the public response stays version/url. */
public record ConsentPolicySettingsVO(String version, String url, long revision) { }
