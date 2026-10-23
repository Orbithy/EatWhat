package you.v50to.eatwhat.cluster;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.config.SiteProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterChallengePeersTest {
    private final ClusterMember campus = new ClusterMember("campus", UUID.randomUUID(), "CAMPUS", 0);
    private final ClusterMember external = new ClusterMember("external", UUID.randomUUID(), "EXTERNAL", 1);

    private ClusterChallengePeers peers(String site, JdbcTemplate jdbc) {
        var settings = new SiteProperties();
        settings.setId(site);
        return new ClusterChallengePeers(settings, new ClusterMembershipView(jdbc), null);
    }

    private JdbcTemplate members(List<ClusterMember> members) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        when(jdbc.queryForList("SELECT members::text AS members FROM eatwhat_cluster.local_state WHERE singleton"))
                .thenReturn(List.of(Map.of("members", JsonMapper.builder().build().writeValueAsString(members))));
        return jdbc;
    }

    @Test void bothMembersChooseTheSameOwnerRegardlessOfMemberOrderOrReachability() {
        // No channel is supplied: choosing an owner must never probe or depend on peer health.
        var a = peers("campus", members(List.of(campus, external)));
        var b = peers("external", members(List.of(external, campus)));
        var owners = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String key = "challenge-" + i;
            String owner = a.ownerOf(key);
            assertEquals(owner, b.ownerOf(key));
            owners.add(owner);
        }
        assertEquals(Set.of("campus", "external"), owners);
    }

    @Test void singleSiteNeedsNoPeerButMembershipFailureDoesNotSelectALocalFallback() {
        var jdbc = members(List.of());
        var peers = peers("campus", jdbc);
        assertEquals("campus", peers.ownerOf("challenge"));
        when(jdbc.queryForList("SELECT members::text AS members FROM eatwhat_cluster.local_state WHERE singleton"))
                .thenThrow(new IllegalStateException("membership unavailable"));
        assertThrows(IllegalStateException.class, () -> peers.ownerOf("challenge"));
    }
}
