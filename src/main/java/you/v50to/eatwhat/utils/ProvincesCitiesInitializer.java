package you.v50to.eatwhat.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.config.SiteProperties;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 省市数据初始化器
 * LEGACY 保留 pc.json 历史补齐；显式站点只接受发布的固定 ID 字典。
 */
@Slf4j
@Component
public class ProvincesCitiesInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SiteProperties siteProperties;

    /** Retain the existing two-argument entry point for standalone legacy callers. */
    public ProvincesCitiesInitializer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.siteProperties = new SiteProperties();
    }

    @Autowired
    public ProvincesCitiesInitializer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                      ObjectProvider<SiteProperties> siteProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.siteProperties = siteProperties.getIfAvailable(SiteProperties::new);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(String... args) throws Exception {
        // 同一数据库中的初始化任务串行执行；事务结束或异常回滚时自动释放。
        // 只协调本初始化器，不锁住正常业务查询或修改已有省市 ID。
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(1161909335, 1)");
        if (siteProperties.getMode() != SiteProperties.Mode.LEGACY) {
            initializePublishedDictionary();
            return;
        }
        log.info("开始检查并补齐省市数据...");
        initializeProvincesCities();
        log.info("省市数据检查与补齐完成");
    }

    private void initializePublishedDictionary() {
        ProvinceCityManifest manifest = ProvinceCityManifest.load(objectMapper);
        // Readers remain available; concurrent manual DML cannot race the compatibility check.
        jdbcTemplate.execute("LOCK TABLE provinces, cities IN SHARE ROW EXCLUSIVE MODE");
        var receipts = jdbcTemplate.queryForList("""
                SELECT version, content_sha256, source_sha256
                FROM reference_dictionary_manifest WHERE dictionary_key = ?
                """, manifest.dictionaryKey());
        if (!receipts.isEmpty()) {
            var receipt = receipts.getFirst();
            if (!manifest.version().equals(receipt.get("version"))
                    || !manifest.contentSha256().equals(receipt.get("content_sha256"))
                    || !manifest.sourceSha256().equals(receipt.get("source_sha256"))) {
                throw incompatibleDictionary("registered version or checksum differs from this release");
            }
        }

        Map<Integer, ProvinceCityManifest.ProvinceEntry> expectedProvinces = manifest.provinces().stream()
                .collect(Collectors.toMap(ProvinceCityManifest.ProvinceEntry::id, Function.identity()));
        Map<Integer, ProvinceCityManifest.CityEntry> expectedCities = manifest.cities().stream()
                .collect(Collectors.toMap(ProvinceCityManifest.CityEntry::id, Function.identity()));
        var existingProvinces = publishedProvinces();
        var existingCities = publishedCities();
        requireCanonicalSubset(existingProvinces, expectedProvinces, "province");
        requireCanonicalSubset(existingCities, expectedCities, "city");
        if (!receipts.isEmpty()
                && (!expectedProvinces.equals(existingProvinces) || !expectedCities.equals(existingCities))) {
            throw incompatibleDictionary("registered dictionary is incomplete; a consistent restore is required");
        }

        var missingProvinces = manifest.provinces().stream()
                .filter(row -> !existingProvinces.containsKey(row.id())).toList();
        if (!missingProvinces.isEmpty()) {
            jdbcTemplate.batchUpdate("INSERT INTO provinces(id, name) VALUES (?, ?)",
                    missingProvinces, missingProvinces.size(), (statement, province) -> {
                        statement.setInt(1, province.id());
                        statement.setString(2, province.name());
                    });
        }
        var missingCities = manifest.cities().stream()
                .filter(row -> !existingCities.containsKey(row.id())).toList();
        if (!missingCities.isEmpty()) {
            jdbcTemplate.batchUpdate("INSERT INTO cities(id, province_id, name) VALUES (?, ?, ?)",
                    missingCities, missingCities.size(), (statement, city) -> {
                        statement.setInt(1, city.id());
                        statement.setInt(2, city.provinceId());
                        statement.setString(3, city.name());
                    });
        }
        // Also catches any unexpected trigger that rewrites or suppresses an inserted reference row.
        if (!expectedProvinces.equals(publishedProvinces()) || !expectedCities.equals(publishedCities())) {
            throw incompatibleDictionary("installed rows do not match the published manifest");
        }
        // The application role has USAGE/SELECT, not UPDATE, on sequences. This no-argument
        // migration-owned function can only advance the two fixed reference sequences.
        jdbcTemplate.execute("SELECT public.advance_published_reference_sequences()");
        if (receipts.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO reference_dictionary_manifest(dictionary_key, version, content_sha256, source_sha256)
                    VALUES (?, ?, ?, ?)
                    """, manifest.dictionaryKey(), manifest.version(), manifest.contentSha256(), manifest.sourceSha256());
        }
        log.info("省市发布字典 {} 验证完成：{} 个省份，{} 个城市", manifest.version(),
                expectedProvinces.size(), expectedCities.size());
    }

    private Map<Integer, ProvinceCityManifest.ProvinceEntry> publishedProvinces() {
        return jdbcTemplate.query("SELECT id, name FROM provinces", (row, index) ->
                        new ProvinceCityManifest.ProvinceEntry(row.getInt("id"), row.getString("name")))
                .stream().collect(Collectors.toMap(ProvinceCityManifest.ProvinceEntry::id, Function.identity()));
    }

    private Map<Integer, ProvinceCityManifest.CityEntry> publishedCities() {
        return jdbcTemplate.query("SELECT id, province_id, name FROM cities", (row, index) ->
                        new ProvinceCityManifest.CityEntry(row.getInt("id"), row.getInt("province_id"), row.getString("name")))
                .stream().collect(Collectors.toMap(ProvinceCityManifest.CityEntry::id, Function.identity()));
    }

    private <T> void requireCanonicalSubset(Map<Integer, T> actual, Map<Integer, T> expected, String kind) {
        for (var row : actual.entrySet()) {
            if (!row.getValue().equals(expected.get(row.getKey()))) {
                throw incompatibleDictionary(kind + " row " + row.getKey() + " differs from the published manifest");
            }
        }
    }

    private IllegalStateException incompatibleDictionary(String reason) {
        return new IllegalStateException("Published reference dictionary rejected: " + reason
                + "; preserve existing IDs and review an authoritative historical manifest");
    }

    private void initializeProvincesCities() throws IOException {
        // 读取 pc.json
        ClassPathResource resource = new ClassPathResource("pc.json");
        Map<String, List<String>> pcData;
        try (var input = resource.getInputStream()) {
            pcData = objectMapper.readValue(input, new TypeReference<Map<String, List<String>>>() {});
        }

        // 插入省份和城市
        pcData.forEach((provinceName, cities) -> {
            // 仅补缺失省份；已有行保持原 ID，重复启动也不消耗其自增序列。
            jdbcTemplate.update(
                """
                INSERT INTO provinces (name)
                SELECT ? WHERE NOT EXISTS (SELECT 1 FROM provinces WHERE name = ?)
                ON CONFLICT (name) DO NOTHING
                """,
                provinceName,
                provinceName
            );

            // 获取省份ID
            Integer provinceId = jdbcTemplate.queryForObject(
                "SELECT id FROM provinces WHERE name = ?",
                Integer.class,
                provinceName
            );

            // 批量插入城市
            if (provinceId != null && cities != null && !cities.isEmpty()) {
                String sql = """
                        INSERT INTO cities (province_id, name)
                        SELECT ?, ? WHERE NOT EXISTS (
                            SELECT 1 FROM cities WHERE province_id = ? AND name = ?
                        )
                        ON CONFLICT (province_id, name) DO NOTHING
                        """;
                
                jdbcTemplate.batchUpdate(sql, cities, cities.size(),
                    (ps, city) -> {
                        ps.setInt(1, provinceId);
                        ps.setString(2, city);
                        ps.setInt(3, provinceId);
                        ps.setString(4, city);
                    }
                );
            }
        });

        log.info("已检查 {} 个参考省份，保留已有省市及额外人工数据", pcData.size());
    }
}

