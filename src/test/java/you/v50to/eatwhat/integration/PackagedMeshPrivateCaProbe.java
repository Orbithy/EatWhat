package you.v50to.eatwhat.integration;

import java.nio.file.*;
import you.v50to.eatwhat.cluster.ClusterMeshSynchronizer;

/** Run the real TLS mesh regression through the exact production candidate's Boot loader. */
public final class PackagedMeshPrivateCaProbe {
    public static void main(String[] args)throws Exception {
        var source=ClusterMeshSynchronizer.class.getResource("ClusterMeshSynchronizer.class").toString();
        if(!source.contains("BOOT-INF/classes"))throw new AssertionError("Candidate Boot classes required");
        var test=new NodePrivateCaHttpIT();test.root=Path.of(args[0]);Files.createDirectories(test.root);
        test.membershipSynchronizationUsesConfiguredPrivateCaForItsOwnHttpClient();
        System.out.println("{\"status\":\"PASS\",\"scope\":\"EXACT_BOOT_CANDIDATE_REAL_TLS_MESH_AND_UNTRUSTED_REJECTION\"}");
    }
}
