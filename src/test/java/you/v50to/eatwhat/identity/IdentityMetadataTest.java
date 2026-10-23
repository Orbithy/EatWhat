package you.v50to.eatwhat.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import you.v50to.eatwhat.config.JacksonConfig;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.po.*;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static you.v50to.eatwhat.identity.IdentityManifestService.*;

class IdentityMetadataTest {
    private final Baseline baseline = new Baseline(UUID.randomUUID(), MANIFEST_VERSION);

    @Test
    void entityNamespacesAreExplicitAndUserInfoSharesItsUserIdentity() {
        assertEquals(19, EntityType.independentTypes().size());
        assertEquals(19, EntityType.independentTypes().stream().map(EntityType::tableName).distinct().count());
        for (EntityType type : EntityType.values()) {
            assertEquals(type, EntityType.fromTableName(type.tableName()));
        }
        assertEquals(EntityType.USERS, EntityType.USER_INFO.identityType());
        assertNotEquals(EntityType.FOODS, EntityType.ACTIVITY_FOODS);
        for (String untrusted : List.of("food", "dinner", "provinces", "cities", "users; DROP TABLE users", "USERS")) {
            assertThrows(IllegalArgumentException.class, () -> EntityType.fromTableName(untrusted));
        }
    }

    @Test
    void manifestBoundsOrderAndVersionRejectMalformedOperatorInput() {
        Entry first = new Entry(1, UUID.randomUUID(), null);
        Entry last = new Entry(HISTORICAL_MAX_ID, UUID.randomUUID(), null);
        assertEquals(HISTORICAL_MAX_ID,
                new ManifestPage(baseline, EntityType.USERS, 0, HISTORICAL_MAX_ID, List.of(first, last), true).nextCursor());
        assertThrows(IllegalArgumentException.class, () -> new Baseline(UUID.randomUUID(), 2));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(HISTORICAL_MAX_ID + 1, UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(1, UUID.randomUUID(), "not a site"));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestPage(baseline, EntityType.USER_INFO, 0, 1, List.of(first), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestPage(baseline, EntityType.USERS, 0, HISTORICAL_MAX_ID, List.of(last, first), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestPage(baseline, EntityType.USERS, 0, 1, List.of(first, first), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestPage(baseline, EntityType.USERS, 0, 1, List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestPage(baseline, EntityType.USERS, 0, 2, List.of(first), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestPage(baseline, EntityType.USERS, 0, 1, List.of(), true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAMPUS", "EXTERNAL"})
    void historyToolsCannotBeConstructedForExplicitSiteModes(String mode) {
        assertThrows(IllegalArgumentException.class,
                () -> new IdentityManifestService(mock(Connection.class), SiteProperties.Mode.valueOf(mode)));
    }

    @Test
    void allExistingBusinessPosKeepNumericIdsAndDoNotExposeGlobalIdentityFields() throws Exception {
        var mapper = new JacksonConfig().objectMapper();
        List<Class<?>> businessTypes = List.of(User.class, Contact.class, Verification.class, ActivityFood.class,
                ActivityDinner.class, ActivityLike.class, Notification.class, Follow.class, Privacy.class, Hub.class,
                Restaurant.class, Food.class, BrowseHistory.class, FoodTag.class, FoodTagging.class,
                FoodFavorite.class, LikeRecord.class, UserInfo.class, Province.class, City.class);
        for (Class<?> businessType : businessTypes) {
            Object business = businessType.getDeclaredConstructor().newInstance();
            var idField = businessType.getDeclaredField("id");
            Object numericId;
            if (idField.getType() == Integer.class) {
                numericId = Integer.valueOf(4201);
            } else {
                numericId = Long.valueOf(1_000_000_001L);
            }
            businessType.getMethod("setId", idField.getType()).invoke(business, numericId);
            var json = mapper.valueToTree(business);
            assertTrue(json.get("id").isIntegralNumber(), businessType.getName());
            assertEquals(((Number) numericId).longValue(), json.get("id").asLong());
            assertFalse(json.has("globalId"), businessType.getName());
            assertFalse(json.has("global_id"), businessType.getName());
            assertFalse(json.has("gid"), businessType.getName());
        }
    }
}
