package you.v50to.eatwhat.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.controller.AuthController;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.enums.Scene;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.service.AuthService;
import you.v50to.eatwhat.service.SmsService;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Real PostgreSQL transactions, alias claims and multiwriter capture through the HTTP endpoint. */
class LoginUsernameIT extends MvcContractSupport {
    static GenericContainer<?> postgres;
    EventFoundationTestSupport.Database db;
    JdbcTemplate jdbc;
    MultiwriterAccounts accounts;
    UserMapper users;
    SmsService sms;
    MockMvc http;
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeAll static void start() { postgres = IsolatedContainers.startedPostgresWithInspectRetry("login-username"); }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }

    @BeforeEach void setup() throws Exception {
        var site = new SiteProperties(); site.setMode(SiteProperties.Mode.CAMPUS);
        site.setId("username-test"); site.setInstanceId(UUID.randomUUID());
        db = EventFoundationTestSupport.createDatabase(postgres, site);
        jdbc = db.runtime();
        db.admin().update("UPDATE eatwhat_account_multiwriter.configuration SET enabled=true");
        accounts = new MultiwriterAccounts(jdbc, db.transactions(), site, true);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(UserMapper.class); config.addMapper(ContactMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(db.dataSource()); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        users = session.getMapper(UserMapper.class);
        sms = mock(SmsService.class);
        when(sms.verifyCode(any(), anyString(), anyString(), anyLong())).thenReturn(true);
        var service = new AuthService();
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "contactMapper", session.getMapper(ContactMapper.class));
        ReflectionTestUtils.setField(service, "passwordEncoder", encoder);
        ReflectionTestUtils.setField(service, "smsService", sms);
        ReflectionTestUtils.setField(service, "transactionManager", db.transactionManager());
        ReflectionTestUtils.setField(service, "multiwriter", accounts);
        var controller = new AuthController();
        ReflectionTestUtils.setField(controller, "authService", service);
        http = mvc(controller);
    }

    long create(String handle, String phone) {
        long id = jdbc.queryForObject("INSERT INTO users(nick_name,display_name,password_hash) VALUES(?,'同学0001',?) RETURNING id", Long.class, handle, User.UNSET_PASSWORD_PREFIX + UUID.randomUUID());
        accounts.initialize(id);
        accounts.command(id, id, () -> jdbc.update("INSERT INTO contacts(account_id,phone) VALUES(?,?)", id, phone));
        return id;
    }

    @Test void combinedSetupCapturesBothCredentialsAndReturnsSeparateProfileFields() throws Exception {
        long id = create("p_000000000001", "13800000001");
        assertNull(users.selectUserInfoById(id).getLoginUsername());
        assertFalse(users.selectUserInfoById(id).getHasUsername());
        String token = loginAs(id);
        http.perform(post("/auth/username/set").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student_1\",\"code\":\"654321\",\"newPassword\":\"new-secret\"}"))
                .andExpect(jsonPath("$.code").value(0));
        var info = users.selectUserInfoById(id);
        assertEquals("同学0001", info.getUserName());
        assertEquals("student_1", info.getLoginUsername());
        assertTrue(info.getHasUsername()); assertTrue(info.getHasPassword());
        assertEquals("student_1", accounts.state(id, "username").value());
        assertTrue(encoder.matches("new-secret", accounts.state(id, "password").value()));
        assertEquals(id, accounts.resolveLogin("student_1"));
        assertNull(accounts.resolveLogin("p_000000000001"));
        verify(sms).verifyCode(Scene.change, "13800000001", "654321", id);
    }

    @Test void hiddenAliasConflictRemainsOccupied() throws Exception {
        long first = create("first", "13800000001"), second = create("second", "13800000002");
        // Reproduce the projection of two independent sites claiming the same login alias.
        db.admin().update("UPDATE eatwhat_account_multiwriter.alias_claim SET value='shared' WHERE kind='username' AND subject_id IN (SELECT global_id FROM eatwhat_identity.entity_identity WHERE entity_type='users' AND numeric_id IN (?,?))", first, second);
        // DriverManagerDataSource uses a new connection per call: use one callback for this fixture.
        db.admin().execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role=replica");
                statement.execute("UPDATE users SET nick_name='~mw~'||id WHERE nick_name IN ('first','second')");
            }
            return null;
        });
        assertNull(accounts.resolveLogin("shared"));
        assertFalse(users.selectUserInfoById(first).getHasUsername());
        http.perform(post("/auth/checkUsername").param("username", "shared"))
                .andExpect(jsonPath("$.code").value(BizCode.USERNAME_EXISTS.getCode()));
    }

    @Test void simultaneousClaimsHaveOneWinnerAndRollBackTheLosersPasswordAndEvents() throws Exception {
        long first = create("p_000000000001", "13800000001"), second = create("p_000000000002", "13800000002");
        String firstToken = loginAs(first), secondToken = loginAs(second);
        long beforeFirst = accounts.operations(first).size(), beforeSecond = accounts.operations(second).size();
        var ready = new CountDownLatch(2);
        when(sms.verifyCode(any(), anyString(), anyString(), anyLong())).thenAnswer(call -> {
            ready.countDown(); assertTrue(ready.await(10, TimeUnit.SECONDS)); return true;
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attempts = List.of(firstToken, secondToken).stream().map(token -> pool.submit(() -> {
                String body = http.perform(post("/auth/username/set").header(TOKEN_HEADER, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"student_1\",\"code\":\"654321\",\"newPassword\":\"new-secret\"}"))
                        .andReturn().getResponse().getContentAsString();
                return tools.jackson.databind.json.JsonMapper.builder().build().readTree(body).path("code").asInt();
            })).toList();
            var codes = List.of(attempts.getFirst().get(20, TimeUnit.SECONDS), attempts.getLast().get(20, TimeUnit.SECONDS));
            assertEquals(1, codes.stream().filter(code -> code == 0).count());
            assertEquals(1, codes.stream().filter(code -> code == BizCode.USERNAME_EXISTS.getCode()).count());
        }
        long loser = "student_1".equals(users.selectUserInfoById(first).getLoginUsername()) ? second : first;
        assertFalse(users.selectUserInfoById(loser).getHasUsername());
        assertFalse(users.selectUserInfoById(loser).getHasPassword());
        assertEquals(loser == first ? beforeFirst : beforeSecond, accounts.operations(loser).size());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM users WHERE nick_name='student_1'", Long.class));
    }
}
