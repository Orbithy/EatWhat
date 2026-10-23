package you.v50to.eatwhat.sync.catalog;

import org.junit.jupiter.api.Test;
import you.v50to.eatwhat.config.SiteProperties;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CatalogSyncPropertiesTest {
    @Test
    void domainDefaultsOffAndUsesBoundedImportAndBackfillBudgets() {
        CatalogSyncProperties properties = new CatalogSyncProperties();
        assertFalse(properties.isEnabled());
        assertEquals(32, properties.getBackfillBatchSize());
        assertEquals(100, properties.getMaxHubImportBatchSize());
        assertEquals(500, properties.getMaxHubAssignments());
        assertEquals(Duration.ofSeconds(30), properties.getBackfillLeaseDuration());

        SiteProperties site = site(SiteProperties.Mode.CAMPUS, "campus-test");
        properties.setPeerSiteId("external-test");
        assertDoesNotThrow(() -> properties.validate(site, "external-test"));
    }

    @Test
    void aCatalogPeerMustBeExplicitAndMatchTheGlobalPeer() {
        CatalogSyncProperties properties = new CatalogSyncProperties();
        SiteProperties site = site(SiteProperties.Mode.CAMPUS, "campus-test");
        assertThrows(IllegalArgumentException.class, () -> properties.validate(site, "external-test"));

        properties.setPeerSiteId("campus-test");
        assertThrows(IllegalArgumentException.class, () -> properties.validate(site, "campus-test"));
        properties.setPeerSiteId("another-site");
        assertThrows(IllegalArgumentException.class, () -> properties.validate(site, "external-test"));

        properties.setPeerSiteId("external-test");
        assertThrows(IllegalArgumentException.class, () -> properties.validate(site(SiteProperties.Mode.LEGACY,
                "legacy-test"), "external-test"));
    }

    @Test
    void importAndAssignmentLimitsCannotBeRaisedPastTheProtocolCaps() {
        CatalogSyncProperties properties = new CatalogSyncProperties();
        properties.setPeerSiteId("external-test");
        SiteProperties site = site(SiteProperties.Mode.CAMPUS, "campus-test");

        properties.setMaxHubImportBatchSize(101);
        assertThrows(IllegalArgumentException.class, () -> properties.validate(site, "external-test"));
        properties.setMaxHubImportBatchSize(100);
        properties.setMaxHubAssignments(501);
        assertThrows(IllegalArgumentException.class, () -> properties.validate(site, "external-test"));
        properties.setMaxHubAssignments(500);
        properties.setBackfillBatchSize(129);
        assertThrows(IllegalArgumentException.class, () -> properties.validate(site, "external-test"));
    }

    private static SiteProperties site(SiteProperties.Mode mode, String id) {
        SiteProperties site = new SiteProperties();
        site.setMode(mode);
        site.setId(id);
        site.setInstanceId(UUID.randomUUID());
        return site;
    }
}
