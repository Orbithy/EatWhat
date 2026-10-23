package you.v50to.eatwhat.contract;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.config.GlobalExceptionHandler;
import you.v50to.eatwhat.config.SecurityConfig;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.controller.AuthController;
import you.v50to.eatwhat.controller.CallbackController;
import you.v50to.eatwhat.controller.UploadController;
import you.v50to.eatwhat.controller.UserController;
import you.v50to.eatwhat.data.dto.LoginDTO;
import you.v50to.eatwhat.data.dto.RegisterDTO;
import you.v50to.eatwhat.data.dto.UserInfoDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Contact;
import you.v50to.eatwhat.data.po.Privacy;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserMapper;
import you.v50to.eatwhat.mapper.VerificationMapper;
import you.v50to.eatwhat.service.AuthService;
import you.v50to.eatwhat.service.EmailService;
import you.v50to.eatwhat.service.UserService;
import you.v50to.eatwhat.service.storage.ObjectStorageService;
import you.v50to.eatwhat.support.MvcContractSupport;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC, BCrypt, Sa-Token and opt-in account adapter; only persistence and node transport are mocked. */
class AccountAuthContractTest extends MvcContractSupport {
    private static final long ID = 1_000_000_001L;
    private static final UUID GID = UUID.fromString("dd4102d1-8773-401a-bcde-83a4c7264d2b");
    private static final String PASSWORD = "synthetic-password";
    private static final String POLICY = "synthetic-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ConsentStore store;
    private SiteProperties site;
    private UserMapper users;
    private ContactMapper contacts;
    private PrivacyMapper privacy;
    private VerificationMapper verifications;
    private EmailService emails;
    private UserService userBusiness;
    private ObjectStorageService storage;
    private StringRedisTemplate redis;
    private PlatformTransactionManager transactions;
    private AccountChannel channel;
    private AccountAuthService accounts;
    private PasswordEncoder encoder;
    private MockMvc http;
    private LocalValidatorFactoryBean validator;
    private final AtomicReference<User> account = new AtomicReference<>();
    private final AtomicReference<ConsentModels.State> state = new AtomicReference<>();
    private final AtomicBoolean committed = new AtomicBoolean();

    @BeforeEach void configure() {
        store = mock(ConsentStore.class, withSettings().mockMaker(MockMakers.INLINE));
        users = mock(UserMapper.class); contacts = mock(ContactMapper.class); privacy = mock(PrivacyMapper.class);
        verifications = mock(VerificationMapper.class); emails = mock(EmailService.class);
        userBusiness = mock(UserService.class); storage = mock(ObjectStorageService.class);
        redis = mock(StringRedisTemplate.class); channel = mock(AccountChannel.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        doAnswer(ignored -> { committed.set(true); return null; }).when(transactions).commit(any());
        encoder = new SecurityConfig().passwordEncoder();
        site = new SiteProperties(); site.setMode(SiteProperties.Mode.CAMPUS); site.setId("campus-test"); site.setInstanceId(UUID.randomUUID());
        accounts = new AccountAuthService(store, site, encoder, users, contacts, privacy, transactions,
                channel, POLICY, "external-test", redis);
        when(users.selectOne(any())).thenAnswer(ignored -> account.get());
        when(users.selectById(anyLong())).thenAnswer(ignored -> account.get());
        when(store.get(anyLong())).thenAnswer(ignored -> state.get());
        when(store.isAuthorized(anyLong())).thenAnswer(ignored -> state.get() != null && "GRANTED".equals(state.get().status())
                && state.get().identityReady() && state.get().withdrawalRequestId() == null
                && state.get().validUntil() != null && state.get().validUntil().isAfter(Instant.now()));
        doAnswer(call -> {
            User created = call.getArgument(0); created.setId(ID); created.setBanned(false); account.set(created); return 1;
        }).when(users).insert(any(User.class));
        when(store.onRegistered(anyLong(), anyBoolean(), nullable(String.class))).thenAnswer(call -> {
            String status = call.getArgument(1, Boolean.class) ? (accounts.external() ? "PROVISIONAL" : "GRANTED") : "NOT_GRANTED";
            state.set(state(status, 1, 1, false)); return state.get();
        });
        when(userBusiness.getInfo(ID)).thenReturn(Result.ok(new UserInfoDTO()));
        AuthService auth = new AuthService();
        // These contracts cover the username registration that deployments now keep closed by default.
        ReflectionTestUtils.setField(auth, "usernameRegistrationEnabled", true);
        ReflectionTestUtils.setField(auth, "accountAuth", accounts);
        ReflectionTestUtils.setField(auth, "userMapper", users);
        ReflectionTestUtils.setField(auth, "verificationMapper", verifications);
        ReflectionTestUtils.setField(auth, "emailService", emails);
        ReflectionTestUtils.setField(auth, "transactionManager", transactions);
        AuthController authController = new AuthController(); ReflectionTestUtils.setField(authController, "authService", auth);
        CallbackController callback = new CallbackController(); ReflectionTestUtils.setField(callback, "authService", auth);
        UserController userController = new UserController(); ReflectionTestUtils.setField(userController, "userService", userBusiness);
        UploadController upload = new UploadController(); ReflectionTestUtils.setField(upload, "objectStorageService", storage);
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        http = MockMvcBuilders.standaloneSetup(authController, callback, userController, upload, new AccountConsentController(accounts))
                .setControllerAdvice(new AuthPrivacyExceptionHandler(), new GlobalExceptionHandler())
                .setMessageConverters(wireConverter())
                .setValidator(validator).addInterceptors(new AccountAccessGate(accounts), new SaInterceptor()).build();
    }

    @AfterEach void closeValidator() { validator.close(); }

    @Test void campusOldRegistrationBodyCommitsBeforeIssuingOrdinarySession() throws Exception {
        StpUtil.setStpLogic(new StpLogic(StpUtil.TYPE) {
            @Override public void login(Object loginId, SaLoginParameter parameter) {
                assertTrue(committed.get(), "No Redis session before database commit");
                super.login(loginId, parameter);
            }
        });
        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenName").value(TOKEN_HEADER)).andExpect(jsonPath("$.data.loginId").value(Long.toString(ID)));
        verify(store).onRegistered(ID, false, null);
        verify(privacy).insert(any(Privacy.class));
        verifyNoInteractions(channel);
    }

    @Test void browsingPolicyPublishesOnlyVersionAndDocumentUrlAndRejectsOldPolicy() throws Exception {
        var current = new AccountAuthService(store, site, encoder, users, contacts, privacy, transactions,
                channel, BrowseConsentPolicy.VERSION, "external-test", redis);
        var publicHttp = MockMvcBuilders.standaloneSetup(new AccountConsentController(current))
                .setControllerAdvice(new AuthPrivacyExceptionHandler(), new GlobalExceptionHandler())
                .setMessageConverters(wireConverter())
                .setValidator(validator).build();
        for (SiteProperties.Mode mode : List.of(SiteProperties.Mode.CAMPUS, SiteProperties.Mode.EXTERNAL)) {
            site.setMode(mode);
            publicHttp.perform(get("/auth/consent/policy")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").value(aMapWithSize(2)))
                    .andExpect(jsonPath("$.data.version").value(BrowseConsentPolicy.VERSION))
                    .andExpect(jsonPath("$.data.url").value("/api" + BrowseConsentPolicy.DOCUMENT));
            publicHttp.perform(post("/auth/consent/grant").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"proof\":\"synthetic-old-proof\",\"policyVersion\":\"cross-border-v1\"}"))
                    .andExpect(jsonPath("$.code").value(BizCode.CONSENT_POLICY_CHANGED.getCode()));
        }
        verifyNoInteractions(store, channel);
    }

    @Test void manualPreferencePolicyPublishesOnlyVersionAndDocumentUrlAndRejectsOldPolicy() throws Exception {
        var current = new AccountAuthService(store, site, encoder, users, contacts, privacy, transactions,
                channel, PreferenceConsentPolicy.VERSION, "external-test", redis);
        var publicHttp = MockMvcBuilders.standaloneSetup(new AccountConsentController(current))
                .setControllerAdvice(new AuthPrivacyExceptionHandler(), new GlobalExceptionHandler())
                .setMessageConverters(wireConverter())
                .setValidator(validator).build();
        for (SiteProperties.Mode mode : List.of(SiteProperties.Mode.CAMPUS, SiteProperties.Mode.EXTERNAL)) {
            site.setMode(mode);
            publicHttp.perform(get("/auth/consent/policy")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").value(aMapWithSize(2)))
                    .andExpect(jsonPath("$.data.version").value(PreferenceConsentPolicy.VERSION))
                    .andExpect(jsonPath("$.data.url").value("/api" + PreferenceConsentPolicy.DOCUMENT));
            publicHttp.perform(post("/auth/consent/grant").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"proof\":\"synthetic-old-proof\",\"policyVersion\":\"cross-border-browse-v2\"}"))
                    .andExpect(jsonPath("$.code").value(BizCode.CONSENT_POLICY_CHANGED.getCode()));
        }
        verifyNoInteractions(store, channel);
    }

    @Test void registrationCommitFailureCannotLeaveAnIssuedSession() throws Exception {
        doThrow(new IllegalStateException("synthetic commit failure")).when(transactions).commit(any());
        Set<String> keys = keys();
        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(90004)).andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(keys, keys());
        assertNull(StpUtil.getSessionByLoginId(ID, false));
    }

    @Test void administratorPublishedPolicyDrivesPublicReadGrantAndExternalAccessWithoutRestart() throws Exception {
        var policies=mock(ConsentPolicyService.class);
        when(policies.current()).thenReturn(new you.v50to.eatwhat.data.vo.ConsentPolicyVO("published-v4","https://example.com/v4.html"));
        accounts.setPolicySettings(policies);
        http.perform(get("/auth/consent/policy"))
                .andExpect(jsonPath("$.data").value(aMapWithSize(2)))
                .andExpect(jsonPath("$.data.version").value("published-v4"))
                .andExpect(jsonPath("$.data.url").value("https://example.com/v4.html"));
        http.perform(post("/auth/consent/grant").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proof\":\"old-proof\",\"policyVersion\":\""+POLICY+"\"}"))
                .andExpect(jsonPath("$.code").value(BizCode.CONSENT_POLICY_CHANGED.getCode()));
        givenAccount();state.set(state("GRANTED",1,1,true));site.setMode(SiteProperties.Mode.EXTERNAL);
        assertEquals(BizCode.CONSENT_POLICY_CHANGED,
                assertThrows(you.v50to.eatwhat.exception.BizException.class,()->accounts.assertExternalAccess(ID)).getBizCode());
        verify(store,never()).grantWithProof(anyString(),anyString(),anyString());
    }

    @Test void policyUpgradeLetsPasswordAndPhoneLoginObtainProofForFreshConsent() throws Exception {
        givenAccount();state.set(state("GRANTED",1,1,true));
        site.setMode(SiteProperties.Mode.EXTERNAL);site.setId("external-test");
        var policies=mock(ConsentPolicyService.class);
        when(policies.current()).thenReturn(new you.v50to.eatwhat.data.vo.ConsentPolicyVO("published-v4","/v4.html"));
        accounts.setPolicySettings(policies);
        when(store.multiwriterEnabled()).thenReturn(true);
        when(store.issueProof(ID,"external-test")).thenReturn("new-policy-proof");
        var multiwriter=mock(you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts.class,withSettings().mockMaker(MockMakers.INLINE));
        when(multiwriter.state(ID,"password")).thenReturn(new you.v50to.eatwhat.account.multiwriter.AccountOperationGraph.Reduced("synthetic",false,true,Set.of()));
        ReflectionTestUtils.setField(accounts,"multiwriter",multiwriter);
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice",PASSWORD)))
                .andExpect(jsonPath("$.code").value(BizCode.CROSS_BORDER_CONSENT_REQUIRED.getCode()))
                .andExpect(jsonPath("$.data.consentProof").value("new-policy-proof"))
                .andExpect(jsonPath("$.data.tokenValue").doesNotExist());
        var phone=accounts.loginVerifiedUser(account.get(),"WEB");
        assertEquals(BizCode.CROSS_BORDER_CONSENT_REQUIRED.getCode(),phone.getCode());
        assertEquals("new-policy-proof",JSON.valueToTree(phone.getData()).path("consentProof").stringValue());
        assertTrue(keys().isEmpty(),"A changed policy must not mint a business session before renewed consent");
    }

    @ParameterizedTest @ValueSource(strings = {"", ",\"crossBorderConsent\":false", ",\"crossBorderConsent\":null"})
    void externalRegistrationRequiresExplicitConsentBeforeAccountChecksOrWrites(String choice) throws Exception {
        external();
        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(with(credentials("alice", PASSWORD), choice)))
                .andExpect(jsonPath("$.code").value(20005)).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(users, privacy, store, channel, transactions);
    }

    @Test void externalRegistrationWithCurrentConsentIsAcceptedOfflineAsProvisionalWithoutSession() throws Exception {
        external();
        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(with(credentials("alice", PASSWORD), ",\"crossBorderConsent\":true,\"consentPolicyVersion\":\"" + POLICY + "\"")))
                .andExpect(jsonPath("$.code").value(20006)).andExpect(jsonPath("$.data.registrationAccepted").value(true))
                .andExpect(jsonPath("$.data.status").value("PROVISIONAL")).andExpect(jsonPath("$.data.identityReady").value(false))
                .andExpect(jsonPath("$.data.subjectId").doesNotExist()).andExpect(jsonPath("$.data.tokenValue").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertTrue(committed.get());
        assertTrue(keys().isEmpty());
        verify(store).onRegistered(ID, true, POLICY);
        verifyNoInteractions(channel);
    }

    @Test void staleRegistrationPolicyIsRejectedWithoutCreatingRows() throws Exception {
        external();
        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(with(credentials("alice", PASSWORD), ",\"crossBorderConsent\":true,\"consentPolicyVersion\":\"old\"")))
                .andExpect(jsonPath("$.code").value(20009));
        verifyNoInteractions(users, privacy, store, channel, transactions);
    }

    @ParameterizedTest @ValueSource(strings = {"PROVISIONAL", "CONFLICT"})
    void uncoordinatedLocalIdentityNeverRelaysBySameNicknameOrCreatesSession(String status) throws Exception {
        external(); givenAccount(); state.set(state(status, 1, 1, false));
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20006)).andExpect(jsonPath("$.data.status").value(status))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(channel);
        assertTrue(keys().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"alice", "synthetic@example.invalid", "13800000000"})
    void readyExternalAccountLogsInLocallyAndBindsDurableVersions(String identifier) throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        var result = http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials(identifier, PASSWORD)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.tokenName").value(TOKEN_HEADER)).andReturn();
        String token = JSON.readTree(result.getResponse().getContentAsString()).at("/data/tokenValue").stringValue();
        assertEquals("2", String.valueOf(StpUtil.getTokenSessionByToken(token).get(AccountAuthService.SESSION_EPOCH)));
        assertEquals("7", String.valueOf(StpUtil.getTokenSessionByToken(token).get(AccountAuthService.SESSION_REVISION)));
        verify(redis).delete(List.of("auth:" + ID, "role:" + ID));
        verifyNoInteractions(channel);
        http.perform(get("/user/info").header(TOKEN_HEADER, "Bearer " + token)).andExpect(jsonPath("$.code").value(0));
    }

    @Test void ordinaryExternalSessionsDenyChangedRevisionAndExpiryButAllowSameVersionRenewal() throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        String token = markedToken();
        http.perform(get("/user/info").header(TOKEN_HEADER, "Bearer " + token)).andExpect(jsonPath("$.code").value(0));
        state.set(state("GRANTED", 2, 8, true));
        http.perform(get("/user/info").header(TOKEN_HEADER, "Bearer " + token)).andExpect(jsonPath("$.code").value(20010));
        state.set(state("GRANTED", 2, 7, true));
        http.perform(get("/user/info").header(TOKEN_HEADER, "Bearer " + token)).andExpect(jsonPath("$.code").value(0));
        state.set(new ConsentModels.State(ID, GID, 2, 7, "GRANTED", POLICY, Instant.EPOCH, true, "ACCOUNT_READY", "NOT_REQUIRED", null));
        http.perform(get("/user/info").header(TOKEN_HEADER, "Bearer " + token)).andExpect(jsonPath("$.code").value(20010));
        verify(userBusiness, times(2)).getInfo(ID);
    }

    @Test void legacyTokenWithoutMarkersCannotUseBusinessAdminOrAvatarRoutes() throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        String token = loginAs(ID, "admin");
        http.perform(get("/user/info").header(TOKEN_HEADER, "Bearer " + token)).andExpect(jsonPath("$.code").value(20010));
        http.perform(get("/user/list").header(TOKEN_HEADER, "Bearer " + token)).andExpect(jsonPath("$.code").value(20010));
        http.perform(post("/upload/presign").header(TOKEN_HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(20010));
        verifyNoInteractions(userBusiness, storage);
    }

    @Test void withdrawnExternalSessionIsBlockedEvenWhenItsRoleCacheOrTokenStillExists() throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        String token = markedToken(); state.set(state("WITHDRAWN", 3, 8, false));
        http.perform(get("/user/info").header(TOKEN_HEADER, "Bearer " + token)).andExpect(jsonPath("$.code").value(20005));
        http.perform(post("/auth/logout").header(TOKEN_HEADER, "Bearer " + token)).andExpect(jsonPath("$.code").value(0));
        verifyNoInteractions(userBusiness);
    }

    @Test void tokenMintRacingARevisionChangeRevokesOnlyTheNewAttempt() throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        AtomicReference<String> minted = new AtomicReference<>();
        StpUtil.setStpLogic(new StpLogic(StpUtil.TYPE) {
            @Override public void login(Object id, SaLoginParameter parameter) {
                super.login(id, parameter); minted.set(StpUtil.getTokenValue()); state.set(state("GRANTED", 2, 8, true));
            }
        });
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20010));
        assertNotNull(minted.get());
        assertNull(StpUtil.getLoginIdByToken(minted.get()));
    }

    @Test void cacheFailureDoesNotMintReadyExternalToken() throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        doThrow(new IllegalStateException("synthetic redis unavailable")).when(redis).delete(anyCollection());
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20007)).andExpect(header().doesNotExist("Set-Cookie"));
        assertTrue(keys().isEmpty());
    }

    @Test void pendingSessionCleanupPreventsMintingANewExternalToken() throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        when(store.hasPendingEffects(ID)).thenReturn(true);
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20006)).andExpect(jsonPath("$.data.tokenValue").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertTrue(keys().isEmpty());
        verifyNoInteractions(redis, channel);
    }

    @Test void cleanupBecomingPendingDuringTokenCreationRevokesOnlyTheAttempt() throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        AtomicBoolean cleanupPending = new AtomicBoolean();
        AtomicReference<String> minted = new AtomicReference<>();
        when(store.hasPendingEffects(ID)).thenAnswer(ignored -> cleanupPending.get());
        StpUtil.setStpLogic(new StpLogic(StpUtil.TYPE) {
            @Override public void login(Object id, SaLoginParameter parameter) {
                super.login(id, parameter);
                minted.set(StpUtil.getTokenValue());
                cleanupPending.set(true);
            }
        });
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20006)).andExpect(jsonPath("$.data.tokenValue").doesNotExist());
        assertNotNull(minted.get());
        assertNull(StpUtil.getLoginIdByToken(minted.get()));
        verify(store, times(2)).hasPendingEffects(ID);
        verifyNoInteractions(channel);
    }

    @Test void staleCleanupPreservesCurrentGrantSessionAndRemovesOlderOrUnmarkedSessions() throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        SaManager.getConfig().setIsConcurrent(true);
        String old = markedToken();
        String unmarked = loginAs(ID);
        state.set(state("GRANTED", 2, 8, true));
        when(store.hasPendingEffects(ID)).thenReturn(false); // Another worker has completed cleanup.
        var response = http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String current = JSON.readTree(response).at("/data/tokenValue").stringValue();
        assertNotNull(StpUtil.getLoginIdByToken(old));
        assertNotNull(StpUtil.getLoginIdByToken(unmarked));

        // A second worker still holds the old pending-effects user list.
        accounts.invalidateLocalSessions(ID);

        assertNull(StpUtil.getLoginIdByToken(old));
        assertNull(StpUtil.getLoginIdByToken(unmarked));
        assertEquals(Long.toString(ID), String.valueOf(StpUtil.getLoginIdByToken(current)));
        http.perform(get("/user/info").header(TOKEN_HEADER, "Bearer " + current)).andExpect(jsonPath("$.code").value(0));
        verify(redis, times(2)).delete(List.of("auth:" + ID, "role:" + ID));
    }

    @Test void withdrawalCleanupRevokesEvenTokensWithMatchingVersionMarkers() {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        SaManager.getConfig().setIsConcurrent(true);
        String first = markedToken();
        String second = markedToken();
        state.set(state("WITHDRAWN", 2, 7, false));
        accounts.invalidateLocalSessions(ID);
        assertNull(StpUtil.getLoginIdByToken(first));
        assertNull(StpUtil.getLoginIdByToken(second));
        verify(redis).delete(List.of("auth:" + ID, "role:" + ID));
    }

    @Test void relayUnavailableIsNotReportedAsMissingAccountWrongPasswordOrNoConsent() throws Exception {
        external(); when(channel.call(eq("authenticate"), any())).thenThrow(new AccountUnavailableException());
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("unknown", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20007)).andExpect(header().doesNotExist("Set-Cookie"));
        verify(users, never()).insert(any(User.class));
        verifyNoInteractions(transactions, privacy);
        assertTrue(keys().isEmpty());
    }

    @Test void staleKnownIdentityRelayIncludesExpectedSubjectAndOnlyReturnsBoundedProgress() throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, false));
        when(channel.call(eq("authenticate"), any())).thenAnswer(call -> {
            JsonNode body = call.getArgument(1);
            assertEquals(GID.toString(), body.get("expectedSubjectId").stringValue());
            return JSON.createObjectNode().put("code", 20006);
        });
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20006)).andExpect(header().doesNotExist("Set-Cookie"));
        assertTrue(keys().isEmpty());
    }

    @Test void campusRelayVerifiesIdentityBeforeConsentAndNeverCreatesBrowserSession() {
        givenAccount(); state.set(state("NOT_GRANTED", 0, 1, false));
        when(store.issueProof(ID, "external-test")).thenReturn("opaque-synthetic-proof");
        var bad = accounts.handle("authenticate", JSON.createObjectNode().put("identifier", "alice").put("password", "wrong"));
        assertEquals(30004, bad.get("code").intValue()); verifyNoInteractions(store);
        var accepted = accounts.handle("authenticate", JSON.createObjectNode().put("identifier", "alice").put("password", PASSWORD));
        assertEquals(20005, accepted.get("code").intValue());
        assertEquals("opaque-synthetic-proof", accepted.get("consentProof").stringValue());
        assertFalse(accepted.toString().contains(PASSWORD)); assertFalse(accepted.toString().contains("passwordHash"));
        assertTrue(keys().isEmpty());
    }

    @Test void oldPolicyProgressDoesNotDiscardProofForAcceptingTheCurrentPolicy() throws Exception {
        givenAccount();
        state.set(new ConsentModels.State(ID, GID, 2, 7, "GRANTED", "synthetic-v0", null, false,
                "ACCOUNT_PENDING", "NOT_REQUIRED", null));
        when(store.issueProof(ID, "external-test")).thenReturn("current-policy-proof");
        JsonNode response = accounts.handle("authenticate", JSON.createObjectNode()
                .put("identifier", "alice").put("password", PASSWORD));
        assertEquals(20005, response.path("code").intValue());
        assertEquals("synthetic-v0", response.path("state").path("policyVersion").stringValue());
        external(); account.set(null);
        when(channel.call(eq("authenticate"), any())).thenReturn(response);
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20005))
                .andExpect(jsonPath("$.data.consentProof").value("current-policy-proof"))
                .andExpect(jsonPath("$.data.policyVersion").value("synthetic-v0"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        http.perform(get("/auth/consent/policy"))
                .andExpect(jsonPath("$.data").value(aMapWithSize(2)))
                .andExpect(jsonPath("$.data.version").value(POLICY))
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.nullValue()));
        when(channel.call(eq("grant"), any())).thenReturn(JSON.createObjectNode().put("code", 20006));
        http.perform(post("/auth/consent/grant").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proof\":\"current-policy-proof\",\"policyVersion\":\"" + POLICY + "\"}"))
                .andExpect(jsonPath("$.code").value(20006));
        assertTrue(keys().isEmpty());
    }

    @Test void relayedAndLocalConsentProgressNeverExposeValidUntil() throws Exception {
        external(); givenAccount(); state.set(state("PROVISIONAL", 1, 1, false));
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20006))
                .andExpect(jsonPath("$.data.status").value("PROVISIONAL"))
                .andExpect(jsonPath("$.data.validUntil").doesNotExist());

        account.set(null);
        ObjectNode peer = JSON.createObjectNode().put("code", 20006);
        peer.putObject("state").put("status", "GRANTED").put("policyVersion", POLICY).put("epoch", 2).put("revision", 7)
                .put("validUntil", "2099-01-01T00:00:00Z").put("identityReady", false)
                .put("backfillStatus", "ACCOUNT_PENDING").put("cleanupStatus", "NOT_REQUIRED").put("registrationAccepted", false);
        when(channel.call(eq("authenticate"), any())).thenReturn(peer);
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20006))
                .andExpect(jsonPath("$.data.status").value("GRANTED"))
                .andExpect(jsonPath("$.data.validUntil").doesNotExist());
        assertTrue(keys().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"policy with spaces", "政策-v1", "/invalid-start"})
    void relayRejectsNonAsciiOrMalformedPolicyVersions(String policy) throws Exception {
        givenAccount();
        state.set(new ConsentModels.State(ID, GID, 0, 1, "NOT_GRANTED", policy, null, false,
                "ACCOUNT_PENDING", "NOT_REQUIRED", null));
        when(store.issueProof(ID, "external-test")).thenReturn("opaque-synthetic-proof");
        JsonNode response = accounts.handle("authenticate", JSON.createObjectNode()
                .put("identifier", "alice").put("password", PASSWORD));
        external(); account.set(null);
        when(channel.call(eq("authenticate"), any())).thenReturn(response);
        http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials("alice", PASSWORD)))
                .andExpect(jsonPath("$.code").value(20007)).andExpect(jsonPath("$.data.consentProof").doesNotExist());
        assertTrue(keys().isEmpty());
    }

    @Test void campusRelayNeverDisclosesSameNicknameOtherSubjectConsent() {
        givenAccount(); state.set(state("NOT_GRANTED", 0, 1, false));
        var response = accounts.handle("authenticate", JSON.createObjectNode().put("identifier", "alice").put("password", PASSWORD)
                .put("expectedSubjectId", UUID.randomUUID().toString()));
        assertEquals(30004, response.get("code").intValue());
        assertFalse(response.has("state")); assertFalse(response.has("consentProof"));
        verify(store, never()).issueProof(anyLong(), anyString());
    }

    @Test void passwordChangeDuringProofIssueDoesNotReturnNewSecurityVersionProofToOldPasswordHolder() {
        givenAccount(); state.set(state("NOT_GRANTED", 0, 1, false));
        when(store.issueProof(ID, "external-test")).thenAnswer(ignored -> {
            User changed = new User(); changed.setId(ID); changed.setPasswordHash(encoder.encode("new-password")); changed.setBanned(false);
            account.set(changed); return "must-not-be-returned";
        });
        var response = accounts.handle("authenticate", JSON.createObjectNode().put("identifier", "alice").put("password", PASSWORD));
        assertEquals(30004, response.get("code").intValue()); assertFalse(response.has("consentProof"));
    }

    @Test void grantProofIsNotASaTokenAndProofReplayMapsToDedicatedCode() throws Exception {
        http.perform(get("/user/info").header(TOKEN_HEADER, "Bearer opaque-synthetic-proof"))
                .andExpect(jsonPath("$.code").value(20001));
        when(store.grantWithProof("opaque-synthetic-proof", "external-test", POLICY))
                .thenThrow(new ConsentStore.ConsentException("PROOF_INVALID"));
        http.perform(post("/auth/consent/grant").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proof\":\"opaque-synthetic-proof\",\"policyVersion\":\"" + POLICY + "\"}"))
                .andExpect(jsonPath("$.code").value(20008)).andExpect(header().string("Cache-Control", "no-store"));
        assertTrue(keys().isEmpty());
    }

    @Test void proofGrantOnExternalRelaysWithoutLocallyCreatingAuthorityConsent() throws Exception {
        external();
        when(channel.call(eq("grant"), any())).thenReturn(JSON.createObjectNode().put("code", 20006));
        http.perform(post("/auth/consent/grant").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proof\":\"opaque-synthetic-proof\",\"policyVersion\":\"" + POLICY + "\"}"))
                .andExpect(jsonPath("$.code").value(20006)).andExpect(header().doesNotExist("Set-Cookie"));
        // The read-only mode check is the only permitted store access; no consent state may be touched locally.
        verify(store).multiwriterEnabled();
        verifyNoMoreInteractions(store);
        verifyNoInteractions(transactions, privacy);
    }

    @ParameterizedTest @ValueSource(strings = {"alice", "synthetic@example.invalid", "13800000000"})
    void provisionalAccountWithdrawsUsingOnlyLocalPasswordWithoutSessionOrRelay(String identifier) throws Exception {
        external(); givenAccount(); state.set(state("PROVISIONAL", 1, 1, false));
        var withdrawn = new ConsentModels.State(ID, GID, 1, 2, "WITHDRAWN", POLICY, null, false,
                "WITHDRAWN", "AWAITING_CAMPUS", UUID.randomUUID());
        when(store.withdraw(ID)).thenReturn(withdrawn);
        http.perform(post("/auth/consent/withdraw-with-password").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.createObjectNode().put("username", identifier).put("password", PASSWORD).toString()))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.data.cleanupStatus").value("AWAITING_CAMPUS"))
                .andExpect(jsonPath("$.data.tokenValue").doesNotExist()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(store).withdraw(ID);
        verifyNoInteractions(channel, transactions, privacy);
        assertTrue(keys().isEmpty());
    }

    @Test void passwordWithdrawalDoesNotDistinguishUnknownAccountOrWrongPassword() throws Exception {
        external(); givenAccount();
        String body = JSON.createObjectNode().put("username", "alice").put("password", "wrong-password").toString();
        http.perform(post("/auth/consent/withdraw-with-password").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(30004));
        account.set(null);
        http.perform(post("/auth/consent/withdraw-with-password").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(30004));
        verifyNoInteractions(store, channel, transactions, privacy, redis);
        assertTrue(keys().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"GRANTED", "WITHDRAWN", "CONFLICT"})
    void passwordWithdrawalDoesNotRequireFreshAuthorizationOrMintAnyNewGrant(String status) throws Exception {
        external(); givenAccount();
        state.set(new ConsentModels.State(ID, GID, 1, 3, status, POLICY, Instant.EPOCH, true,
                "ACCOUNT_PENDING", "AWAITING_CAMPUS", null));
        when(store.withdraw(ID)).thenReturn(state("WITHDRAWN", 1, 4, false));
        doThrow(new IllegalStateException("synthetic redis unavailable")).when(redis).delete(anyCollection());
        http.perform(post("/auth/consent/withdraw-with-password").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.createObjectNode().put("username", "alice").put("password", PASSWORD).toString()))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        verify(store).withdraw(ID); verify(store, never()).grant(anyLong(), anyString());
        verifyNoInteractions(channel);
        assertTrue(keys().isEmpty());
    }

    @Test void campusPasswordWithdrawalKeepsExistingCampusSessionAndNeverRelays() throws Exception {
        givenAccount(); String token = loginAs(ID);
        when(store.withdraw(ID)).thenReturn(state("WITHDRAWN", 3, 8, false));
        http.perform(post("/auth/consent/withdraw-with-password").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.createObjectNode().put("username", "alice").put("password", PASSWORD).toString()))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(Long.toString(ID), String.valueOf(StpUtil.getLoginIdByToken(token)));
        verify(store).withdraw(ID); verifyNoInteractions(channel, redis);
    }

    @Test void anonymousEmailLinkCannotUpgradeWithdrawnExternalAccount() throws Exception {
        external(); givenAccount(); state.set(state("WITHDRAWN", 3, 8, false));
        when(emails.verifyToken("synthetic-email-proof")).thenReturn(new EmailService.EmailVerification(ID, "student@example.invalid"));
        http.perform(get("/auth/verifyEmail").param("token", "synthetic-email-proof"))
                .andExpect(jsonPath("$.code").value(20005));
        verifyNoInteractions(verifications);
    }

    @Test void schoolVerificationCannotBypassExternalGate() throws Exception {
        external(); givenAccount(); state.set(state("GRANTED", 2, 7, true));
        String token = markedToken(); state.set(state("WITHDRAWN", 3, 8, false));
        http.perform(get("/auth/sdu").header(TOKEN_HEADER, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(20005));
        // Callbacks no longer use the session; without a state issued by /auth/sdu they write nothing.
        for (String path : List.of("/auth/callback/short", "/callback/short")) {
            http.perform(get(path).param("token", "synthetic-school-assertion").header(TOKEN_HEADER, "Bearer " + token))
                    .andExpect(jsonPath("$.code").value(30012));
        }
        verifyNoInteractions(verifications);
    }

    @Test void sensitiveDtoRenderingAndMalformedAuthenticationErrorsDoNotExposeInputs() throws Exception {
        RegisterDTO registration = new RegisterDTO(); registration.setUsername("name-sentinel"); registration.setPassword("password-sentinel");
        LoginDTO login = new LoginDTO(); login.setUsername("name-sentinel"); login.setPassword("password-sentinel");
        assertEquals("RegisterDTO[redacted]", registration.toString()); assertEquals("LoginDTO[redacted]", login.toString());
        assertEquals("GrantRequest[redacted]", new AccountConsentController.GrantRequest("proof-sentinel", POLICY).toString());
        assertEquals("WithdrawalRequest[redacted]", new AccountConsentController.WithdrawalRequest("name-sentinel", "password-sentinel").toString());
        String response = http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password-sentinel\",\"device\":\"proof-sentinel\"}"))
                .andExpect(jsonPath("$.code").value(10001)).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("sentinel"));
        verifyNoInteractions(users, store, channel);
    }

    private void external() { site.setMode(SiteProperties.Mode.EXTERNAL); }
    private User givenAccount() {
        User user = new User(); user.setId(ID); user.setUserName("alice"); user.setPasswordHash(encoder.encode(PASSWORD));
        user.setRole("user"); user.setBanned(false); account.set(user);
        Contact contact = new Contact(); contact.setAccountId(ID); when(contacts.selectOne(any())).thenReturn(contact);
        return user;
    }
    private static ConsentModels.State state(String status, long epoch, long revision, boolean ready) {
        return new ConsentModels.State(ID, GID, epoch, revision, status, POLICY, Instant.parse("2099-01-01T00:00:00Z"), ready,
                ready ? "ACCOUNT_READY" : "ACCOUNT_PENDING", "NOT_REQUIRED", null);
    }
    private String markedToken() {
        String token = loginAs(ID);
        StpUtil.getTokenSessionByToken(token).set(AccountAuthService.SESSION_EPOCH, state.get().epoch())
                .set(AccountAuthService.SESSION_REVISION, state.get().revision());
        return token;
    }
    private static String credentials(String name, String password) {
        return JSON.createObjectNode().put("username", name).put("password", password).put("device", "WEB").toString();
    }
    private static String with(String object, String fields) { return object.substring(0, object.length() - 1) + fields + "}"; }
    private static Set<String> keys() { return Set.copyOf(SaManager.getSaTokenDao().searchData("", "", 0, -1, true)); }
}
