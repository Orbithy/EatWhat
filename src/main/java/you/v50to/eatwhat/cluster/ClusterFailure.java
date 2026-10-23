package you.v50to.eatwhat.cluster;

/** Stable public code only; SQL, object keys, credentials and peer response bodies are never propagated. */
public final class ClusterFailure extends RuntimeException {
    private final int status;
    public ClusterFailure(int status,String code){super(code);this.status=status;}
    public int status(){return status;}
}
