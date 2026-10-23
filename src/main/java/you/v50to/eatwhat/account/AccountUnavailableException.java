package you.v50to.eatwhat.account;

/** Deliberately excludes URLs, request/response material, nested exceptions and TLS details. */
public final class AccountUnavailableException extends RuntimeException {
    public AccountUnavailableException() {
        super("Account channel unavailable", null, false, true);
    }
}
