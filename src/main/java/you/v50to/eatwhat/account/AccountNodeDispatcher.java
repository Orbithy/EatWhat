package you.v50to.eatwhat.account;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.config.SiteProperties;

import java.util.Set;
import java.util.UUID;

/** Role-specific RPC dispatch after the transport has authenticated and bound the peer identity. */
public final class AccountNodeDispatcher implements AccountRequestHandler {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.account.multiwriter.MultiwriterExchange multiwriter;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.service.SmsService sms;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private you.v50to.eatwhat.service.EmailService email;
    private final SiteProperties site;
    private final ConsentStore store;
    private final AccountAuthService authentication;
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public AccountNodeDispatcher(SiteProperties site, ConsentStore store, AccountAuthService authentication) {
        this.site = site; this.store = store; this.authentication = authentication;
    }

    @Override public JsonNode handle(String operation, JsonNode body) {
        if(operation.startsWith("email-")){if(email==null)throw new IllegalStateException("EMAIL_CHALLENGE_NOT_ACTIVE");return email.handleChallenge(operation,body);}
        if(operation.startsWith("sms-")){if(sms==null)throw new IllegalStateException("SMS_CHALLENGE_NOT_ACTIVE");return sms.handleChallenge(operation,body);}
        if(operation.equals("multiwriter-capabilities"))return multiwriter==null?json.createObjectNode().put("schemaVersion",2).put("active",false):multiwriter.capabilities();
        if(operation.equals("multiwriter-exchange")){if(multiwriter==null)throw new IllegalStateException("ACCOUNT_MULTIWRITER_NOT_ACTIVE");return multiwriter.receive(body);}
        if (site.getMode() == SiteProperties.Mode.CAMPUS) {
            return switch (operation) {
                case "authenticate", "grant" -> authentication.handle(operation, body);
                case "register" -> outcome(store.acceptRegistration(snapshot(body)));
                case "withdraw" -> {
                    if (!body.isObject() || body.size() != 2 || !body.has("subjectId") || !body.has("requestId")) {
                        throw new IllegalArgumentException("Invalid account withdrawal request");
                    }
                    UUID subjectId = UUID.fromString(body.path("subjectId").asText());
                    UUID requestId = UUID.fromString(body.path("requestId").asText());
                    store.acceptWithdrawal(subjectId, requestId);
                    yield outcome("APPLIED").put("epoch", store.withdrawalEpoch(subjectId, requestId));
                }
                default -> throw new IllegalArgumentException("Account operation not allowed on this site");
            };
        }
        if (site.getMode() == SiteProperties.Mode.EXTERNAL && "project".equals(operation)) {
            return outcome(store.apply(snapshot(body)));
        }
        throw new IllegalArgumentException("Account operation not allowed on this site");
    }

    private ConsentModels.Snapshot snapshot(JsonNode body) {
        if (!body.isObject()) throw new IllegalArgumentException("Invalid account snapshot");
        try { return json.treeToValue(body, ConsentModels.Snapshot.class); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid account snapshot"); }
    }

    private tools.jackson.databind.node.ObjectNode outcome(String value) {
        if (!Set.of("APPLIED", "STALE", "REJECTED", "CONFLICT").contains(value)) {
            throw new IllegalStateException("Invalid account acknowledgement");
        }
        return json.createObjectNode().put("status", value);
    }
}
