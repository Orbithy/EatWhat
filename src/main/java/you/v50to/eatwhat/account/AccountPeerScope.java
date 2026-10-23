package you.v50to.eatwhat.account;

import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.util.function.Supplier;

/** Authenticated transport identity only; never populated from an account payload. */
public final class AccountPeerScope {
    private static final ThreadLocal<NodeIdentity> PEER=new ThreadLocal<>();
    private AccountPeerScope(){}
    public static NodeIdentity current(){return PEER.get();}
    public static String audience(String fallback){return PEER.get()==null?fallback:PEER.get().siteId();}
    public static <T> T run(NodeIdentity authenticated,Supplier<T> action){if(PEER.get()!=null)throw new IllegalStateException("Nested account peer scope");PEER.set(authenticated);try{return action.get();}finally{PEER.remove();}}
}
