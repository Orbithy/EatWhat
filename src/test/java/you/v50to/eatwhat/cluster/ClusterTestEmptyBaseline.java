package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;

/** Exercise genuine empty account-baseline progress in protocol integration
 * tests. Event/file transport is deliberately outside this fixture's scope. */
public final class ClusterTestEmptyBaseline {
    private ClusterTestEmptyBaseline(){}
    public static ClusterHttpNodes.Synchronizer synchronizer(JdbcTemplate jdbc,ClusterStore membership){
        var mesh=new ClusterMeshSynchronizer(jdbc,null,null,null,membership,null);
        return (plan,nodes)->{if(!mesh.projectBaseline(plan,nodes))throw new AssertionError("Expected an empty account baseline");};
    }
}
