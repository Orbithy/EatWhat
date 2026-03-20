package you.v50to.eatwhat.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 省市数据初始化器
 * 从 pc.json 读取省市数据并初始化到数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProvincesCitiesInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        // 检查是否已经初始化
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM provinces", Integer.class);
        if (count != null && count > 0) {
            log.info("省市数据已存在，跳过初始化");
            return;
        }

        log.info("开始初始化省市数据...");
        initializeProvincesCities();
        log.info("省市数据初始化完成");
    }

    private void initializeProvincesCities() throws IOException {
        // 读取 pc.json
        ClassPathResource resource = new ClassPathResource("pc.json");
        Map<String, List<String>> pcData = objectMapper.readValue(
            resource.getInputStream(),
            new TypeReference<Map<String, List<String>>>() {}
        );

        // 插入省份和城市
        pcData.forEach((provinceName, cities) -> {
            // 插入省份
            jdbcTemplate.update(
                "INSERT INTO provinces (name) VALUES (?) ON CONFLICT (name) DO NOTHING",
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
                String sql = "INSERT INTO cities (province_id, name) VALUES (?, ?) " +
                             "ON CONFLICT (province_id, name) DO NOTHING";
                
                jdbcTemplate.batchUpdate(sql, cities, cities.size(),
                    (ps, city) -> {
                        ps.setInt(1, provinceId);
                        ps.setString(2, city);
                    }
                );
            }
        });

        log.info("成功初始化 {} 个省份", pcData.size());
    }
}

