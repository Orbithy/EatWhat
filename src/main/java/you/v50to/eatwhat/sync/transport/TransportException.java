package you.v50to.eatwhat.sync.transport;

/** Safe, fixed protocol errors. Parser, SQL, payload and TLS details never become wire errors. */
public final class TransportException extends RuntimeException {
    private final int httpStatus;
    private final String code;

    public TransportException(int httpStatus, String code) {
        super(safeCode(code));
        if (httpStatus < 400 || httpStatus > 599) throw new IllegalArgumentException("Invalid error status");
        this.httpStatus = httpStatus;
        this.code = code;
    }

    private static String safeCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid transport error code");
        }
        return code;
    }

    public int httpStatus() { return httpStatus; }
    public String code() { return code; }
}
