package you.v50to.eatwhat.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.config.JacksonConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProvinceCityManifestTest {
    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    @Test
    void publishedManifestKeepsTheOriginalCleanDatabaseNumericIds() {
        ProvinceCityManifest manifest = ProvinceCityManifest.load(mapper);
        assertEquals("pc-1", manifest.version());
        assertEquals(31, manifest.provinces().size());
        assertEquals(451, manifest.cities().size());
        assertEquals(new ProvinceCityManifest.ProvinceEntry(1, "北京市"), manifest.provinces().getFirst());
        assertEquals(new ProvinceCityManifest.ProvinceEntry(15, "山东省"), manifest.provinces().get(14));
        assertEquals(new ProvinceCityManifest.CityEntry(179, 15, "济南市"), manifest.cities().get(178));
        assertEquals(new ProvinceCityManifest.CityEntry(451, 31, "白杨市"), manifest.cities().getLast());
        assertThrows(UnsupportedOperationException.class, () -> manifest.provinces().clear());
    }

    @Test
    void jsonArrayOrderDoesNotAllocateOrChangeIds() throws Exception {
        ProvinceCityManifest manifest = ProvinceCityManifest.load(mapper);
        var provinces = new ArrayList<>(manifest.provinces());
        var cities = new ArrayList<>(manifest.cities());
        Collections.reverse(provinces);
        Collections.reverse(cities);
        var reordered = copy(manifest, provinces, cities);
        var decoded = read(reordered);
        assertEquals(reordered, decoded);
        assertEquals(ProvinceCityManifest.CONTENT_SHA256, decoded.contentSha256());
    }

    @Test
    void alteredBundledSourceIsRejectedEvenIfManifestIsUntouched() throws Exception {
        byte[] manifest = new ClassPathResource(ProvinceCityManifest.RESOURCE).getContentAsByteArray();
        var failure = assertThrows(IllegalStateException.class,
                () -> ProvinceCityManifest.read(mapper, manifest, "{}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(failure.getMessage().contains("source checksum mismatch"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "schema", "key", "contentHash", "sourceHash"})
    void selfReportedMetadataCannotOverrideTheTrustedRelease(String change) {
        var original = ProvinceCityManifest.load(mapper);
        var changed = new ProvinceCityManifest(change.equals("schema") ? 2 : original.schemaVersion(),
                change.equals("key") ? "another-dictionary" : original.dictionaryKey(),
                change.equals("version") ? "pc-2" : original.version(),
                change.equals("sourceHash") ? "0".repeat(64) : original.sourceSha256(),
                change.equals("contentHash") ? "0".repeat(64) : original.contentSha256(),
                original.provinces(), original.cities());
        assertThrows(IllegalStateException.class, () -> read(changed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"provinceId", "provinceName", "cityId", "cityName", "parent", "negativeId", "blank", "control"})
    void invalidReferenceRowsAreRejectedBeforeTheyReachTheDatabase(String change) {
        var original = ProvinceCityManifest.load(mapper);
        var provinces = new ArrayList<>(original.provinces());
        var cities = new ArrayList<>(original.cities());
        switch (change) {
            case "provinceId" -> provinces.set(1, new ProvinceCityManifest.ProvinceEntry(1, "天津市"));
            case "provinceName" -> provinces.set(1, new ProvinceCityManifest.ProvinceEntry(2, "北京市"));
            case "cityId" -> cities.set(1, new ProvinceCityManifest.CityEntry(1, 1, "西城区"));
            case "cityName" -> cities.set(1, new ProvinceCityManifest.CityEntry(2, 1, "东城区"));
            case "parent" -> cities.set(0, new ProvinceCityManifest.CityEntry(1, 999, "东城区"));
            case "negativeId" -> provinces.set(0, new ProvinceCityManifest.ProvinceEntry(-1, "北京市"));
            case "blank" -> provinces.set(0, new ProvinceCityManifest.ProvinceEntry(1, " "));
            case "control" -> provinces.set(0, new ProvinceCityManifest.ProvinceEntry(1, "北\t京"));
            default -> fail("Unknown test mutation");
        }
        var failure = assertThrows(IllegalStateException.class, () -> read(copy(original, provinces, cities)));
        assertTrue(failure.getMessage().contains("invalid or duplicate"));
    }

    @Test
    void validLookingButDifferentNameCannotRetainThePublishedChecksum() {
        var original = ProvinceCityManifest.load(mapper);
        var cities = new ArrayList<>(original.cities());
        cities.set(178, new ProvinceCityManifest.CityEntry(179, 15, "synthetic-other-city"));
        var failure = assertThrows(IllegalStateException.class,
                () -> read(copy(original, original.provinces(), cities)));
        assertTrue(failure.getMessage().contains("content checksum mismatch"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{", "[]", "{}"})
    void malformedOrEmptyDocumentsAreRejected(String json) {
        assertThrows(IllegalStateException.class, () -> ProvinceCityManifest.read(mapper,
                json.getBytes(StandardCharsets.UTF_8), source()));
    }

    private ProvinceCityManifest copy(ProvinceCityManifest original,
                                      List<ProvinceCityManifest.ProvinceEntry> provinces,
                                      List<ProvinceCityManifest.CityEntry> cities) {
        return new ProvinceCityManifest(original.schemaVersion(), original.dictionaryKey(), original.version(),
                original.sourceSha256(), original.contentSha256(), provinces, cities);
    }

    private ProvinceCityManifest read(ProvinceCityManifest manifest) throws Exception {
        return ProvinceCityManifest.read(mapper, mapper.writeValueAsBytes(manifest), source());
    }

    private byte[] source() throws Exception {
        return new ClassPathResource("pc.json").getContentAsByteArray();
    }
}
