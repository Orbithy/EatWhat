package you.v50to.eatwhat.utils;

/** Login identifiers are distinct from non-unique display names. */
public final class LoginUsernameRules {
    public static final String PATTERN = "^[A-Za-z0-9_]{3,10}$";

    private LoginUsernameRules() { }

    public static boolean valid(String value) {
        return value != null && value.matches(PATTERN);
    }

    public static boolean generated(String value) {
        return value != null && value.matches("^p_[0-9a-f]{12}$");
    }
}
