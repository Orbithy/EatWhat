package you.v50to.eatwhat.account;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.data.dto.LoginDTO;
import you.v50to.eatwhat.data.dto.RegisterDTO;
import you.v50to.eatwhat.data.enums.BizCode;
import you.v50to.eatwhat.data.po.Contact;
import you.v50to.eatwhat.data.po.Privacy;
import you.v50to.eatwhat.data.po.User;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.vo.ConsentPolicyVO;
import you.v50to.eatwhat.exception.BizException;
import you.v50to.eatwhat.mapper.ContactMapper;
import you.v50to.eatwhat.mapper.PrivacyMapper;
import you.v50to.eatwhat.mapper.UserMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Opt-in authentication adapter. Credentials and remote assertions live only in the current request. */
public final class AccountAuthService implements AccountRequestHandler {
    private ConsentPolicyService policySettings;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setPolicySettings(ConsentPolicyService settings){this.policySettings=settings;}
    private String currentPolicyVersion(){return policySettings==null?policyVersion:policySettings.current().version();}
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AccountAuthService.class);
    public static final String SESSION_EPOCH = "account:consentEpoch";
    public static final String SESSION_REVISION = "account:revision";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConsentStore store;
    private final SiteProperties site;
    private final PasswordEncoder encoder;
    private final UserMapper users;
    private final ContactMapper contacts;
    private final PrivacyMapper privacy;
    private final TransactionTemplate transactions;
    private final AccountChannel channel;
    private final String policyVersion;
    private final String peerSiteId;
    private final StringRedisTemplate redis;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<you.v50to.eatwhat.cluster.ClusterSessions> clusterSessions;

    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.control.ControlAccountSecurity controlAccountSecurity;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.account.multiwriter.MultiwriterAccounts multiwriter;

    public AccountAuthService(ConsentStore store, SiteProperties site, PasswordEncoder encoder,
                              UserMapper users, ContactMapper contacts, PrivacyMapper privacy,
                              PlatformTransactionManager transactions, AccountChannel channel,
                              String policyVersion, String peerSiteId, StringRedisTemplate redis) {
        this.store = Objects.requireNonNull(store); this.site = Objects.requireNonNull(site);
        this.encoder = Objects.requireNonNull(encoder); this.users = Objects.requireNonNull(users);
        this.contacts = Objects.requireNonNull(contacts); this.privacy = Objects.requireNonNull(privacy);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
        this.channel = channel; this.policyVersion = Objects.requireNonNull(policyVersion);
        this.peerSiteId = site.getClusterId()==null?Objects.requireNonNull(peerSiteId):peerSiteId; this.redis = Objects.requireNonNull(redis);
    }

    public Result<?> register(RegisterDTO request) {
        boolean grant = Boolean.TRUE.equals(request.getCrossBorderConsent());
        if (external() && !grant) return Result.fail(BizCode.CROSS_BORDER_CONSENT_REQUIRED);
        if (grant && !currentPolicyVersion().equals(request.getConsentPolicyVersion())) return Result.fail(BizCode.CONSENT_POLICY_CHANGED);
        // Joining an outer transaction would allow a session to escape before its eventual commit.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return Result.fail(BizCode.AUTH_UNAVAILABLE);
        if (users.exists(new LambdaQueryWrapper<User>().eq(User::getUserName, request.getUsername()))) {
            return Result.fail(BizCode.USERNAME_EXISTS);
        }
        try {
            User user = transactions.execute(status -> {
                User created = new User();
                created.setUserName(request.getUsername());
                created.setPasswordHash(encoder.encode(request.getPassword()));
                users.insert(created);
                Privacy defaults = new Privacy();
                defaults.setAccountId(created.getId()); defaults.setFollowing(true); defaults.setFollower(true);
                privacy.insert(defaults);
                store.onRegistered(created.getId(), grant, request.getConsentPolicyVersion());
                return created;
            });
            if (external()&&!store.multiwriterEnabled()) return pending(store.get(user.getId()), true);
            return issueSession(user, request.getDevice().name());
        } catch (ConsentStore.ConsentException failure) { return Result.fail(consentCode(failure)); }
        catch (DuplicateKeyException conflict) { return Result.fail(BizCode.USERNAME_EXISTS); }
    }

    public Result<?> login(LoginDTO request) {
        User user = findUser(request.getUsername());
        if (user != null && !user.hasPassword()) return Result.fail(BizCode.PASSWORD_NOT_SET);
        if(store.multiwriterEnabled()) {
            if(user==null)return Result.fail(BizCode.USER_NOT_FOUND);
            if(multiwriter.state(user.getId(),"password").conflict())return Result.fail(BizCode.ACCOUNT_SECURITY_CONFLICT);
            if(!passwordMatches(request.getPassword(),user))return Result.fail(BizCode.PASSWORD_ERROR);
            if(disabled(user))return Result.fail(BizCode.USER_DISABLED);
            var local=store.get(user.getId());
            if(external()&&(local==null||!"GRANTED".equals(local.status())||!currentPolicyVersion().equals(local.policyVersion()))){String proof=store.issueProof(user.getId(),site.getId());return relayResult(wire(BizCode.CROSS_BORDER_CONSENT_REQUIRED,store.get(user.getId()),proof));}
            return issueSession(user,request.getDevice().name());
        }
        if (!external()) {
            if (user == null) return Result.fail(BizCode.USER_NOT_FOUND);
            if (!passwordMatches(request.getPassword(), user)) return Result.fail(BizCode.PASSWORD_ERROR);
            return issueSession(user, request.getDevice().name());
        }
        ConsentModels.State state = user == null ? null : store.get(user.getId());
        if (state != null && ("PROVISIONAL".equals(state.status()) || "CONFLICT".equals(state.status()))) {
            // A same-name campus account is not this provisional identity. Never merge by login alias.
            if (!passwordMatches(request.getPassword(), user)) return Result.fail(BizCode.PASSWORD_ERROR);
            if (disabled(user)) return Result.fail(BizCode.USER_DISABLED);
            return pending(state, false);
        }
        if (state != null && store.isAuthorized(user.getId())) {
            if (!passwordMatches(request.getPassword(), user)) return Result.fail(BizCode.PASSWORD_ERROR);
            return issueSession(user, request.getDevice().name());
        }
        if (channel == null) return Result.fail(BizCode.AUTH_UNAVAILABLE);
        ObjectNode body = JSON.createObjectNode().put("identifier", request.getUsername()).put("password", request.getPassword());
        if (state != null) body.put("expectedSubjectId", state.subjectId().toString());
        try {
            // A successful authority check still cannot manufacture a ready/fresh local projection.
            return relayResult(channel.call("authenticate", body));
        } catch (AccountUnavailableException unavailable) { return Result.fail(BizCode.AUTH_UNAVAILABLE); }
        finally { body.removeAll(); }
    }

    /**
     * The caller has proven ownership of the account's phone with an SMS code. Same authorization rules as a
     * password login, except an external account without a local grant cannot be relayed: the code is site-local.
     */
    public Result<?> loginVerifiedUser(User user, String device) {
        if (disabled(user)) return Result.fail(BizCode.USER_DISABLED);
        if (store.multiwriterEnabled()) {
            var local = store.get(user.getId());
            if (external() && (local == null || !"GRANTED".equals(local.status()) || !currentPolicyVersion().equals(local.policyVersion()))) {
                String proof = store.issueProof(user.getId(), site.getId());
                return relayResult(wire(BizCode.CROSS_BORDER_CONSENT_REQUIRED, store.get(user.getId()), proof));
            }
            return issueSession(user, device);
        }
        if (!external()) return issueSession(user, device);
        ConsentModels.State state = store.get(user.getId());
        if (state != null && ("PROVISIONAL".equals(state.status()) || "CONFLICT".equals(state.status()))) return pending(state, false);
        if (state != null && store.isAuthorized(user.getId())) return issueSession(user, device);
        return Result.fail(BizCode.NOT_SUPPORTED, "本站暂时不能用验证码登录这个账号，请用密码登录");
    }

    /**
     * Phone registration with the same consent rules as {@link #register}. DuplicateKeyException propagates so the
     * caller can log in the account a concurrent request created for the same number.
     */
    public Result<?> registerPhone(Supplier<User> createAccount, Consumer<User> attachPhone,
                                   Boolean crossBorderConsent, String consentPolicyVersion, String device) {
        boolean grant = Boolean.TRUE.equals(crossBorderConsent);
        if (external() && !grant) return Result.fail(BizCode.CROSS_BORDER_CONSENT_REQUIRED);
        if (grant && !currentPolicyVersion().equals(consentPolicyVersion)) return Result.fail(BizCode.CONSENT_POLICY_CHANGED);
        if (TransactionSynchronizationManager.isActualTransactionActive()) return Result.fail(BizCode.AUTH_UNAVAILABLE);
        try {
            User user = transactions.execute(status -> {
                User created = createAccount.get();
                store.onRegistered(created.getId(), grant, consentPolicyVersion);
                attachPhone.accept(created);
                return created;
            });
            if (external()&&!store.multiwriterEnabled()) return pending(store.get(user.getId()), true);
            return issueSession(user, device);
        } catch (ConsentStore.ConsentException failure) { return Result.fail(consentCode(failure)); }
    }

    private Result<?> issueSession(User user, String device) {
        if (disabled(user)) return Result.fail(BizCode.USER_DISABLED);
        if(multiwriter!=null&&multiwriter.enabled())clearAuthorizationCache(user.getId());
        ConsentModels.State before = null;
        if (external()) {
            assertExternalAccess(user.getId());
            before = store.get(user.getId());
            if (store.hasPendingEffects(user.getId())&&!store.multiwriterEnabled()) return pending(before, false);
            // New tokens must never pick up an old cached admin/verified role after projection revision changes.
            clearAuthorizationCache(user.getId());
        }
        Long controlVersion;
        try{controlVersion=controlAccountSecurity==null?null:controlAccountSecurity.loginVersion(user);}
        catch(you.v50to.eatwhat.control.ControlFailure failure){logUnavailable("CONTROL_VERSION",failure);return Result.fail(BizCode.AUTH_UNAVAILABLE);}
        if(controlVersion!=null)clearAuthorizationCache(user.getId());
        if(controlVersion==null)StpUtil.login(user.getId(),device);
        else StpUtil.login(user.getId(),new cn.dev33.satoken.stp.parameter.SaLoginParameter().setDeviceType(device).setIsShare(false));
        String issued = StpUtil.getTokenValue();
        try {
            if(controlAccountSecurity!=null)controlAccountSecurity.stamp(controlVersion);
            User current = users.selectById(user.getId());
            if (current == null || disabled(current)) {
                revokeAttempt(issued);
                return Result.fail(current == null ? BizCode.USER_NOT_FOUND : BizCode.USER_DISABLED);
            }
            if (!Objects.equals(user.getPasswordHash(), current.getPasswordHash())) {
                LOG.warn("Account session unavailable [phase=PASSWORD_CHANGED_DURING_ISSUE]");
                revokeAttempt(issued);
                return Result.fail(BizCode.AUTH_UNAVAILABLE);
            }
            if (external()) {
                assertExternalAccess(user.getId());
                ConsentModels.State after = store.get(user.getId());
                if (store.hasPendingEffects(user.getId())&&!store.multiwriterEnabled()) throw new BizException(BizCode.DATA_SYNC_PENDING);
                if (before == null || after == null || before.epoch() != after.epoch() || before.revision() != after.revision()) {
                    throw new BizException(BizCode.ACCOUNT_AUTHORIZATION_EXPIRED);
                }
                StpUtil.getTokenSession().set(SESSION_EPOCH, after.epoch()).set(SESSION_REVISION, after.revision());
                assertExternalSession(user.getId());
            }
            if(store.multiwriterEnabled()){var localState=store.get(user.getId());if(localState!=null)StpUtil.getTokenSession().set(SESSION_EPOCH,localState.epoch()).set(SESSION_REVISION,localState.revision());}
            if(clusterSessions!=null){var shared=clusterSessions.getIfAvailable();if(shared!=null&&shared.active())shared.register(new you.v50to.eatwhat.cluster.ClusterSessions.Token(issued));}
            var info = StpUtil.getTokenInfo();
            if (StpUtil.hasRole("admin")) info.setTag("admin");
            return Result.ok(info);
        } catch (RuntimeException failure) {
            try { revokeAttempt(issued); } catch (RuntimeException ignored) { /* The persistent gate remains fail-closed. */ }
            if (failure instanceof BizException business) return Result.fail(business.getBizCode());
            logUnavailable("ISSUE_SESSION",failure);
            return Result.fail(BizCode.AUTH_UNAVAILABLE);
        }
    }

    /** Used by anonymous email callbacks after the token has identified its owner. No session marker is required here. */
    public void assertExternalAccess(long userId) {
        if (!external()) return;
        User user = users.selectById(userId);
        if (user == null) throw new BizException(BizCode.ACCOUNT_AUTHORIZATION_EXPIRED);
        if (disabled(user)) throw new BizException(BizCode.USER_DISABLED);
        ConsentModels.State state = store.get(userId);
        if (state == null) throw new BizException(BizCode.ACCOUNT_AUTHORIZATION_EXPIRED);
        if ("WITHDRAWN".equals(state.status()) || "NOT_GRANTED".equals(state.status()) || state.withdrawalRequestId() != null) {
            throw new BizException(BizCode.CROSS_BORDER_CONSENT_REQUIRED);
        }
        if (!"GRANTED".equals(state.status()) || !state.identityReady()) throw new BizException(BizCode.DATA_SYNC_PENDING);
        if (!currentPolicyVersion().equals(state.policyVersion())) throw new BizException(BizCode.CONSENT_POLICY_CHANGED);
        if (!store.isAuthorized(userId)) throw new BizException(BizCode.ACCOUNT_AUTHORIZATION_EXPIRED);
    }

    /** Every ordinary external MVC request validates its token against current durable consent/security revision. */
    public void assertExternalSession(long userId) {
        if (!external()) return;
        assertExternalAccess(userId);
        ConsentModels.State state = store.get(userId);
        var session = StpUtil.getTokenSession();
        if (state == null || !Long.toString(state.epoch()).equals(String.valueOf(session.get(SESSION_EPOCH)))
                || !Long.toString(state.revision()).equals(String.valueOf(session.get(SESSION_REVISION)))) {
            throw new BizException(BizCode.ACCOUNT_AUTHORIZATION_EXPIRED);
        }
    }

    public boolean external() { return site.getMode() == SiteProperties.Mode.EXTERNAL; }

    public Result<ConsentPolicyVO> policy() {
        return Result.ok(policySettings==null?ConsentPolicyVO.of(policyVersion):policySettings.current());
    }

    public Result<?> status() { return Result.ok(view(store.get(StpUtil.getLoginIdAsLong()), false)); }

    public Result<?> grant(String proof, String policy) {
        if (!currentPolicyVersion().equals(policy)) return Result.fail(BizCode.CONSENT_POLICY_CHANGED);
        try {
            if (proof != null) {
                if (proof.isBlank() || proof.length() > 512) return Result.fail(BizCode.CONSENT_PROOF_INVALID);
                if(store.multiwriterEnabled())return Result.ok(view(store.grantWithProof(proof,site.getId(),policy),false));
                if (external()) {
                    if (channel == null) return Result.fail(BizCode.AUTH_UNAVAILABLE);
                    ObjectNode body = JSON.createObjectNode().put("proof", proof).put("policyVersion", policy);
                    try { return relayResult(channel.call("grant", body)); }
                    finally { body.removeAll(); }
                }
                return Result.ok(view(store.grantWithProof(proof, AccountPeerScope.audience(peerSiteId), policy), false));
            }
            long userId = StpUtil.getLoginIdAsLong();
            User user = users.selectById(userId);
            if (user == null || disabled(user)) return Result.fail(BizCode.USER_DISABLED);
            // External sessions are not authority for new grants, even if their previous projection is fresh.
            if (external()&&!store.multiwriterEnabled()) return Result.fail(BizCode.AUTH_UNAVAILABLE);
            return Result.ok(view(store.grant(userId, policy), false));
        } catch (ConsentStore.ConsentException failure) { return Result.fail(consentCode(failure)); }
        catch (AccountUnavailableException unavailable) { return Result.fail(BizCode.AUTH_UNAVAILABLE); }
    }

    public Result<?> withdraw() {
        return withdrawLocal(StpUtil.getLoginIdAsLong());
    }

    /** A provisional/expired account may reduce its local consent without obtaining a business session. */
    public Result<?> withdrawWithPassword(String identifier, String password) {
        User user = findUser(identifier);
        if (user == null || !passwordMatches(password, user)) return Result.fail(BizCode.PASSWORD_ERROR);
        // Never relay an alias: the campus may own a different account with the same name.
        return withdrawLocal(user.getId());
    }

    private Result<?> withdrawLocal(long userId) {
        try {
            if(external()&&!store.multiwriterEnabled()&&clusterSessions!=null){var shared=clusterSessions.getIfAvailable();if(shared!=null)shared.fenceWithdrawal(userId);}
            ConsentModels.State state = store.withdraw(userId);
            if (external()) {
                try { invalidateLocalSessions(userId); }
                catch (AccountUnavailableException unavailable) { /* Durable WITHDRAWN gate denies access; the job retries cleanup. */ }
            }
            return Result.ok(view(state, false));
        } catch (ConsentStore.ConsentException failure) { return Result.fail(consentCode(failure)); }
        catch (you.v50to.eatwhat.cluster.ClusterFailure failure) { return Result.fail(BizCode.AUTH_UNAVAILABLE); }
    }

    /** Root's durable projection/cleanup job retries this operation if Redis is unavailable. */
    public void invalidateLocalSessions(long userId) {
        boolean failed = false;
        try {
            for (String token : List.copyOf(StpUtil.getTokenValueListByLoginId(userId))) {
                var session = StpUtil.getStpLogic().getTokenSessionByToken(token, false);
                Object epoch = session == null ? null : session.get(SESSION_EPOCH);
                Object revision = session == null ? null : session.get(SESSION_REVISION);
                // A stale worker may run after another worker cleared pending effects and a new login completed.
                // Read authority after this token's markers so a newer grant is never compared with old state.
                ConsentModels.State current = store.get(userId);
                boolean belongsToCurrentGrant = current != null && "GRANTED".equals(current.status())
                        && Long.toString(current.epoch()).equals(String.valueOf(epoch))
                        && Long.toString(current.revision()).equals(String.valueOf(revision));
                if (!belongsToCurrentGrant) StpUtil.logoutByTokenValue(token);
            }
        } catch (RuntimeException failure) { failed = true; }
        try { clearAuthorizationCache(userId); } catch (RuntimeException failure) { failed = true; }
        if (failed) throw new AccountUnavailableException();
    }

    private void clearAuthorizationCache(long userId) {
        try { redis.delete(List.of("auth:" + userId, "role:" + userId)); }
        catch (RuntimeException unavailable) { logUnavailable("AUTH_CACHE",unavailable);throw new AccountUnavailableException(); }
    }

    /** Node RPC handler: no calls to StpUtil.login and no credential or assertion persistence. */
    @Override public JsonNode handle(String operation, JsonNode body) {
        if (site.getMode() != SiteProperties.Mode.CAMPUS) return wire(BizCode.AUTH_UNAVAILABLE, null, null);
        try {
            if ("authenticate".equals(operation)) {
                fields(body, Set.of("identifier", "password"), Set.of("expectedSubjectId"));
                String identifier = text(body, "identifier", 64);
                String password = text(body, "password", 4096);
                User user = findUser(identifier);
                if (user == null || !passwordMatches(password, user)) return wire(BizCode.PASSWORD_ERROR, null, null);
                if (disabled(user)) return wire(BizCode.USER_DISABLED, null, null);
                ConsentModels.State state = store.get(user.getId());
                if (body.has("expectedSubjectId")) {
                    UUID expected = UUID.fromString(text(body, "expectedSubjectId", 36));
                    if (state == null || !expected.equals(state.subjectId())) return wire(BizCode.PASSWORD_ERROR, null, null);
                }
                if (state == null || !"GRANTED".equals(state.status()) || !currentPolicyVersion().equals(state.policyVersion()) || site.getClusterId()!=null&&!store.recipientAllowed(state,AccountPeerScope.audience(peerSiteId))) {
                    String proof = store.issueProof(user.getId(), AccountPeerScope.audience(peerSiteId));
                    User current = users.selectById(user.getId());
                    if (current == null || !Objects.equals(user.getPasswordHash(), current.getPasswordHash())) {
                        return wire(BizCode.PASSWORD_ERROR, null, null);
                    }
                    if (disabled(current)) return wire(BizCode.USER_DISABLED, null, null);
                    return wire(BizCode.CROSS_BORDER_CONSENT_REQUIRED, store.get(user.getId()), proof);
                }
                return wire(BizCode.DATA_SYNC_PENDING, state, null);
            }
            if ("grant".equals(operation)) {
                fields(body, Set.of("proof", "policyVersion"), Set.of());
                String policy = text(body, "policyVersion", 128);
                if (!currentPolicyVersion().equals(policy)) return wire(BizCode.CONSENT_POLICY_CHANGED, null, null);
                ConsentModels.State state = store.grantWithProof(text(body, "proof", 512), AccountPeerScope.audience(peerSiteId), policy);
                return wire(BizCode.DATA_SYNC_PENDING, state, null);
            }
            return wire(BizCode.NOT_SUPPORTED, null, null);
        } catch (ConsentStore.ConsentException failure) { return wire(consentCode(failure), null, null); }
        catch (IllegalArgumentException invalid) { return wire(BizCode.PARAM_INVALID, null, null); }
    }

    private Result<?> relayResult(JsonNode response) {
        try {
            fields(response, Set.of("code"), Set.of("state", "consentProof"));
            if (!response.get("code").isIntegralNumber() || !response.get("code").canConvertToInt()) throw new IllegalArgumentException();
            BizCode code = switch (response.get("code").intValue()) {
                case 30004 -> BizCode.PASSWORD_ERROR;
                case 30002 -> BizCode.USER_DISABLED;
                case 20005 -> BizCode.CROSS_BORDER_CONSENT_REQUIRED;
                case 20006 -> BizCode.DATA_SYNC_PENDING;
                case 20007 -> BizCode.AUTH_UNAVAILABLE;
                case 20008 -> BizCode.CONSENT_PROOF_INVALID;
                case 20009 -> BizCode.CONSENT_POLICY_CHANGED;
                default -> throw new IllegalArgumentException();
            };
            ObjectNode data = JSON.createObjectNode();
            if (response.has("consentProof")) {
                if (code != BizCode.CROSS_BORDER_CONSENT_REQUIRED) throw new IllegalArgumentException();
                data.put("consentProof", text(response, "consentProof", 512));
            }
            if (response.has("state")) {
                JsonNode state = response.get("state");
                fields(state, Set.of("status", "policyVersion", "epoch", "revision", "validUntil", "identityReady",
                        "backfillStatus", "cleanupStatus", "registrationAccepted"), Set.of());
                if (!Set.of("NOT_GRANTED", "PROVISIONAL", "GRANTED", "WITHDRAWN", "CONFLICT")
                        .contains(text(state, "status", 32))
                        || !text(state, "policyVersion", 128).matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")
                        || !text(state, "backfillStatus", 64).matches("[A-Z_]{1,64}")
                        || !text(state, "cleanupStatus", 64).matches("[A-Z_]{1,64}")) throw new IllegalArgumentException();
                for (String field : List.of("epoch", "revision")) {
                    if (!state.get(field).isIntegralNumber() || !state.get(field).canConvertToLong()
                            || state.get(field).longValue() < 0) throw new IllegalArgumentException();
                }
                if (!state.get("identityReady").isBoolean() || !state.get("registrationAccepted").isBoolean()) {
                    throw new IllegalArgumentException();
                }
                JsonNode until = state.get("validUntil");
                if (!until.isNull()) Instant.parse(text(state, "validUntil", 64));
                // Match local ConsentView shape: validUntil is an internal replication lease, validated but never
                // exposed to clients; only fixed scalar fields are copied from the authenticated peer.
                for (var property : state.properties()) {
                    if (!property.getKey().equals("validUntil")) data.set(property.getKey(), property.getValue().deepCopy());
                }
            }
            return data.isEmpty() ? Result.fail(code) : Result.fail(code, data);
        } catch (RuntimeException invalid) { return Result.fail(BizCode.AUTH_UNAVAILABLE); }
    }

    private User findUser(String identifier) {
        if(multiwriter!=null&&multiwriter.enabled()){Long id=multiwriter.resolveLogin(identifier);return id==null?null:users.selectById(id);}
        if (identifier.contains("@") || identifier.matches("^\\d{11}$")) {
            var query = new LambdaQueryWrapper<Contact>();
            if (identifier.contains("@")) query.eq(Contact::getEmail, identifier);
            else query.eq(Contact::getPhone, identifier);
            Contact contact = contacts.selectOne(query);
            return contact == null ? null : users.selectById(contact.getAccountId());
        }
        return users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUserName, identifier));
    }

    private boolean passwordMatches(String password, User user) {
        try { return user != null && password != null && user.getPasswordHash() != null && encoder.matches(password, user.getPasswordHash()); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private boolean disabled(User user) { return Boolean.TRUE.equals(user.getBanned()) || (!store.multiwriterEnabled()&&StpUtil.isDisable(user.getId())); }
    private static void revokeAttempt(String token) { StpUtil.setTokenValueToStorage(token); StpUtil.logout(); }

    private Result<?> pending(ConsentModels.State state, boolean accepted) {
        return Result.fail(BizCode.DATA_SYNC_PENDING, view(state, accepted));
    }

    public record ConsentView(String status, String policyVersion, long epoch, long revision,
                              boolean identityReady, String backfillStatus, String cleanupStatus, boolean registrationAccepted) { }

    private ConsentView view(ConsentModels.State state, boolean accepted) {
        if (state == null) return new ConsentView("NOT_GRANTED", currentPolicyVersion(), 0, 0, false,
                "NOT_STARTED", "NOT_REQUIRED", accepted);
        return new ConsentView(state.status(), state.policyVersion(), state.epoch(), state.revision(),
                state.identityReady(), state.backfillStatus(), state.cleanupStatus(), accepted);
    }

    private ObjectNode wire(BizCode code, ConsentModels.State state, String proof) {
        ObjectNode response = JSON.createObjectNode().put("code", code.getCode());
        if (state != null) {
            ObjectNode progress = response.putObject("state").put("status", state.status())
                    .put("policyVersion", state.policyVersion()).put("epoch", state.epoch()).put("revision", state.revision())
                    .put("identityReady", state.identityReady()).put("backfillStatus", state.backfillStatus())
                    .put("cleanupStatus", state.cleanupStatus()).put("registrationAccepted", false);
            if (state.validUntil() == null) progress.putNull("validUntil");
            else progress.put("validUntil", state.validUntil().toString());
        }
        if (proof != null) response.put("consentProof", proof);
        return response;
    }

    private static BizCode consentCode(ConsentStore.ConsentException failure) {
        return switch (failure.code()) {
            case "CONSENT_REQUIRED" -> BizCode.CROSS_BORDER_CONSENT_REQUIRED;
            case "POLICY_CHANGED" -> BizCode.CONSENT_POLICY_CHANGED;
            case "PROOF_INVALID" -> BizCode.CONSENT_PROOF_INVALID;
            case "IDENTITY_CONFLICT" -> BizCode.DATA_SYNC_PENDING;
            default -> BizCode.AUTH_UNAVAILABLE;
        };
    }

    private static String text(JsonNode body, String field, int maximum) {
        JsonNode value = body.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank() || value.stringValue().length() > maximum) {
            throw new IllegalArgumentException("Invalid account request");
        }
        return value.stringValue();
    }

    private static void fields(JsonNode body, Set<String> required, Set<String> optional) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("Invalid account request");
        for (String field : required) if (!body.has(field)) throw new IllegalArgumentException("Invalid account request");
        for (var property : body.properties()) {
            if (!required.contains(property.getKey()) && !optional.contains(property.getKey())) {
                throw new IllegalArgumentException("Invalid account request");
            }
        }
    }
    // Authentication exceptions can contain credentials or SQL parameters. Never log messages or Throwable objects.
    private static void logUnavailable(String phase,RuntimeException failure) {
        String sqlState="";
        Throwable cause=failure;
        for(int depth=0;cause!=null&&depth<8;depth++,cause=cause.getCause())
            if(cause instanceof java.sql.SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}")){sqlState=sql.getSQLState();break;}
        LOG.warn("Account session unavailable [phase={},failure={},sqlState={}]",phase,failure.getClass().getSimpleName(),sqlState);
    }
}
