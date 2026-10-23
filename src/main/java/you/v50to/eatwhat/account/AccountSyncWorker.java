package you.v50to.eatwhat.account;

import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.notification.NotificationReadWorker;
import you.v50to.eatwhat.sync.relation.RelationBackfillWorker;
import you.v50to.eatwhat.sync.profile.ProfileCommands;
import you.v50to.eatwhat.sync.privacy.PrivacyCommands;
import you.v50to.eatwhat.sync.catalog.CatalogBackfillWorker;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable metadata tasks only. Account snapshots and interactive credentials never enter a retry queue. */
public final class AccountSyncWorker {
    @Autowired(required=false) private you.v50to.eatwhat.account.multiwriter.MultiwriterExchange multiwriter;
    private final ConsentStore store;
    private final AccountChannel channel;
    private final AccountAuthService authentication;
    private final SiteProperties site;
    private final Duration leaseDuration;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicBoolean active = new AtomicBoolean();
    private RelationBackfillWorker relationBackfillWorker;
    private NotificationReadWorker notificationReadWorker;
    private ProfileCommands profileCommands;
    private PrivacyCommands privacyCommands;
    private CatalogBackfillWorker catalogBackfillWorker;
    private you.v50to.eatwhat.sync.browse.BrowseCommands browseCommands;
    private you.v50to.eatwhat.sync.preferences.PreferenceCommands preferenceCommands;

    public AccountSyncWorker(ConsentStore store, AccountChannel channel, AccountAuthService authentication,
                             SiteProperties site, Duration leaseDuration) {
        this.store = store; this.channel = channel; this.authentication = authentication;
        this.site = site; this.leaseDuration = leaseDuration;
    }

    public void runOnce() {
        if (Thread.currentThread().isInterrupted() || !active.compareAndSet(false, true)) return;
        try {
            clearEffects();
            if(multiwriter!=null&&multiwriter.writing()){try{multiwriter.synchronize(channel);}catch(RuntimeException unavailable){multiwriter.failed(unavailable);}}
            for (var job : store.claim(4, leaseDuration)) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    if ("CLEANUP".equals(job.kind())) {
                        switch (store.runCleanup(job)) {
                            case "DONE" -> store.complete(job);
                            case "DISCARD" -> store.discard(job);
                            default -> store.retry(job);
                        }
                        continue;
                    }
                    // In multiwriter mode immutable account operations supersede legacy snapshots.
                    // Cleanup remains a durable local task and must not be skipped with old transport jobs.
                    if(multiwriter!=null&&multiwriter.writing()){store.discard(job);continue;}
                    if ("WITHDRAW_REQUEST".equals(job.kind())) {
                        var state = store.get(job.userId());
                        if (state == null || state.withdrawalRequestId() == null) {
                            store.discard(job);
                            continue;
                        }
                        var request = json.createObjectNode().put("subjectId", job.subjectId().toString())
                                .put("requestId", state.withdrawalRequestId().toString());
                        var response = channel.call("withdraw", request);
                        if (!"APPLIED".equals(response.path("status").asText()) || !response.path("epoch").canConvertToLong()) {
                            store.retry(job);
                            continue;
                        }
                        store.acknowledgeWithdrawal(job.subjectId(), state.withdrawalRequestId(), response.path("epoch").asLong());
                        store.complete(job);
                        continue;
                    }
                    var snapshot = store.export(job);
                    if (snapshot == null) { store.discard(job); continue; }
                    String operation = site.getMode() == SiteProperties.Mode.EXTERNAL ? "register" : "project";
                    var response = channel.call(operation, json.valueToTree(snapshot));
                    switch (response.path("status").asText()) {
                        case "APPLIED", "STALE" -> store.complete(job);
                        case "CONFLICT" -> store.conflict(job);
                        default -> store.retry(job);
                    }
                } catch (RuntimeException failure) {
                    // No exception cause/message may contain identifiers, snapshots or passwords.
                    if (!Thread.currentThread().isInterrupted()) {
                        try { store.retry(job); } catch (RuntimeException unavailable) { /* Lease recovery owns the task. */ }
                    }
                }
            }
            clearEffects();
            if (relationBackfillWorker != null && !Thread.currentThread().isInterrupted()) {
                try { relationBackfillWorker.runOnce(); }
                catch (RuntimeException ignored) { /* Durable jobs retry on the next account cycle. */ }
            }
            if (notificationReadWorker != null && !Thread.currentThread().isInterrupted()) {
                try { notificationReadWorker.runOnce(); }
                catch (RuntimeException ignored) { /* Durable read batches retry on the next account cycle. */ }
            }
            if (profileCommands != null && !Thread.currentThread().isInterrupted()) {
                try { profileCommands.runOnce(); }
                catch (RuntimeException ignored) { /* Profile baselines and bridges are retried on the next account cycle. */ }
            }
            if (privacyCommands != null && !Thread.currentThread().isInterrupted()) {
                try { privacyCommands.runOnce(); }
                catch (RuntimeException ignored) { /* Privacy epoch bridges are retried on the next account cycle. */ }
            }
            if (catalogBackfillWorker != null && !Thread.currentThread().isInterrupted()) {
                try { catalogBackfillWorker.runOnce(); }
                catch (RuntimeException ignored) { /* Catalog head bridges retry through durable leases. */ }
            }
            if (browseCommands != null && !Thread.currentThread().isInterrupted()) {
                try { browseCommands.runOnce(); }
                catch (RuntimeException ignored) { /* Unpublished history retries on the next account cycle. */ }
            }
            if (preferenceCommands != null && !Thread.currentThread().isInterrupted()) {
                try { preferenceCommands.runOnce(); }
                catch (RuntimeException ignored) { /* Explicit settings retry through durable state. */ }
            }
        } finally { active.set(false); }
    }

    @Autowired(required = false)
    public void setRelationBackfillWorker(RelationBackfillWorker worker) {
        this.relationBackfillWorker = worker;
    }

    @Autowired(required = false)
    public void setNotificationReadWorker(NotificationReadWorker worker) {
        this.notificationReadWorker = worker;
    }

    @Autowired(required = false)
    public void setProfileCommands(ProfileCommands commands) { this.profileCommands = commands; }

    @Autowired(required = false)
    public void setPrivacyCommands(PrivacyCommands commands) { this.privacyCommands = commands; }

    @Autowired(required = false)
    public void setCatalogBackfillWorker(CatalogBackfillWorker worker) { this.catalogBackfillWorker = worker; }

    @Autowired(required = false)
    public void setBrowseCommands(you.v50to.eatwhat.sync.browse.BrowseCommands commands) { this.browseCommands = commands; }

    @Autowired(required = false)
    public void setPreferenceCommands(you.v50to.eatwhat.sync.preferences.PreferenceCommands commands) { this.preferenceCommands = commands; }

    private void clearEffects() {
        for (long userId : store.pendingEffects(16)) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                long effectsVersion = store.effectsVersion(userId);
                authentication.invalidateLocalSessions(userId);
                store.completeEffects(userId, effectsVersion);
            } catch (RuntimeException unavailable) {
                // The persistent epoch/session gate already denies old authorization. Retry cleanup later.
            }
        }
    }
}
