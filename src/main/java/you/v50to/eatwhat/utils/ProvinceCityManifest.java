package you.v50to.eatwhat.utils;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Published reference IDs, independent of database sequences and JSON object iteration order.
 * Version changes require a reviewed new manifest and compatible migration; IDs are never recycled.
 */
public record ProvinceCityManifest(int schemaVersion, String dictionaryKey, String version,
                                   String sourceSha256, String contentSha256,
                                   List<ProvinceEntry> provinces, List<CityEntry> cities) {
    public static final String RESOURCE = "reference/pc-v1.manifest.json";
    public static final String DICTIONARY_KEY = "province-city";
    public static final String VERSION = "pc-1";
    public static final String SOURCE_SHA256 = "d28f1ecaa92519ac7e759309f23b4a1080e05eb0816c66a9ac0cc55be8aa02e1";
    public static final String CONTENT_SHA256 = "8310da56f36b4906a4a9b6852dc9e2a35baaa3ea42535af0805377748ff51032";

    public ProvinceCityManifest {
        provinces = provinces == null ? List.of() : List.copyOf(provinces);
        cities = cities == null ? List.of() : List.copyOf(cities);
    }

    public record ProvinceEntry(int id, String name) { }
    public record CityEntry(int id, int provinceId, String name) { }
    private record CityName(int provinceId, String name) { }

    public static ProvinceCityManifest load(ObjectMapper mapper) {
        try (var manifest = new ClassPathResource(RESOURCE).getInputStream();
             var source = new ClassPathResource("pc.json").getInputStream()) {
            return read(mapper, manifest.readAllBytes(), source.readAllBytes());
        } catch (IOException failure) {
            throw rejected("bundled resource is unavailable");
        }
    }

    // Package access permits malformed-resource tests without changing the packaged reference data.
    static ProvinceCityManifest read(ObjectMapper mapper, byte[] manifestBytes, byte[] sourceBytes) {
        ProvinceCityManifest manifest;
        try {
            manifest = mapper.readValue(manifestBytes, ProvinceCityManifest.class);
        } catch (RuntimeException failure) {
            throw rejected("invalid JSON document");
        }
        if (manifest == null || manifest.schemaVersion != 1
                || !DICTIONARY_KEY.equals(manifest.dictionaryKey) || !VERSION.equals(manifest.version)) {
            throw rejected("unsupported dictionary version");
        }
        if (!SOURCE_SHA256.equals(manifest.sourceSha256) || !SOURCE_SHA256.equals(sha256(sourceBytes))) {
            throw rejected("source checksum mismatch");
        }
        manifest.validateRows();
        if (!CONTENT_SHA256.equals(manifest.contentSha256)
                || !CONTENT_SHA256.equals(sha256(manifest.canonicalBytes()))) {
            throw rejected("content checksum mismatch");
        }
        return manifest;
    }

    private void validateRows() {
        if (provinces.isEmpty() || cities.isEmpty()) {
            throw rejected("empty reference data");
        }
        Set<Integer> provinceIds = new HashSet<>();
        Set<String> provinceNames = new HashSet<>();
        for (ProvinceEntry province : provinces) {
            if (province.id <= 0 || !validName(province.name)
                    || !provinceIds.add(province.id) || !provinceNames.add(province.name)) {
                throw rejected("invalid or duplicate province");
            }
        }
        Set<Integer> cityIds = new HashSet<>();
        Set<CityName> cityNames = new HashSet<>();
        for (CityEntry city : cities) {
            if (city.id <= 0 || !validName(city.name) || !provinceIds.contains(city.provinceId)
                    || !cityIds.add(city.id) || !cityNames.add(new CityName(city.provinceId, city.name))) {
                throw rejected("invalid or duplicate city");
            }
        }
    }

    private static boolean validName(String name) {
        return name != null && !name.isBlank() && name.equals(name.strip()) && name.length() <= 64
                && name.chars().noneMatch(Character::isISOControl);
    }

    /** UTF-8, LF, ID-sorted rows; tabs/newlines in names are rejected before hashing. */
    private byte[] canonicalBytes() {
        StringBuilder canonical = new StringBuilder("eatwhat.province-city/v1\n");
        provinces.stream().sorted(Comparator.comparingInt(ProvinceEntry::id)).forEach(province ->
                canonical.append("P\t").append(province.id).append('\t').append(province.name).append('\n'));
        cities.stream().sorted(Comparator.comparingInt(CityEntry::id)).forEach(city ->
                canonical.append("C\t").append(city.id).append('\t').append(city.provinceId)
                        .append('\t').append(city.name).append('\n'));
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static IllegalStateException rejected(String reason) {
        return new IllegalStateException("Reference dictionary manifest rejected: " + reason);
    }
}
