package you.v50to.eatwhat.control;

public final class ControlFailure extends RuntimeException {
    public final int status;
    public final String code;
    public ControlFailure(int status, String code) { super(code); this.status=status; this.code=code; }
}
