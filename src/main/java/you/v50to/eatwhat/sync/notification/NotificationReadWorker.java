package you.v50to.eatwhat.sync.notification;

import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.SyncTransactions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static you.v50to.eatwhat.account.ConsentModels.State;

/** Bounded, restart-safe publisher; durable batch membership is never expanded into one large payload. */
public final class NotificationReadWorker {
    private final SyncTransactions transactions;
    private final NotificationReadStore store;
    private final NotificationReadCommands commands;
    private final ConsentStore consent;
    private final int batchSize;
    private final AtomicBoolean active = new AtomicBoolean();

    public NotificationReadWorker(SyncTransactions transactions, NotificationReadStore store,
                                  NotificationReadCommands commands, ConsentStore consent, int batchSize) {
        this.transactions = transactions; this.store = store;
        this.commands = commands; this.consent = consent; this.batchSize = batchSize;
    }

    public void runOnce() {
        if (Thread.currentThread().isInterrupted() || !active.compareAndSet(false, true)) return;
        try {
            transactions.independent(() -> {
                List<NotificationReadStore.Member> candidates = store.pendingMembers(batchSize);
                if (candidates.isEmpty()) return null;
                var subjects = new ArrayList<UUID>();
                candidates.forEach(member -> { subjects.add(member.actorGid()); subjects.add(member.recipientGid()); });
                // Hold all authorization rows before taking any member/domain row locks.
                store.lockConsentSubjects(store.userIdsForSubjects(subjects));
                for (NotificationReadStore.Member candidate : candidates) {
                    if (Thread.currentThread().isInterrupted()) break;
                    NotificationReadStore.Member member = store.lockMember(candidate);
                    if (member == null) continue;
                    store.markAttempted(member);
                    if (member.actorGid() == null) {
                        store.setMemberStatus(member, "SKIP");
                        continue;
                    }
                    process(member);
                }
                return null;
            });
        } finally { active.set(false); }
    }

    private void process(NotificationReadStore.Member member) {
        State actor = consentState(member.actorGid());
        State recipient = consentState(member.recipientGid());
        if (actor == null || recipient == null) {
            store.setMemberStatus(member, "WAIT");
            return;
        }
        boolean epochsAdvanced = actor.epoch() > member.actorEpoch()
                || recipient.epoch() > member.recipientEpoch();
        if (member.priorEventId() != null && store.delivered(member.priorEventId()) && !epochsAdvanced) {
            store.setMemberStatus(member, "DONE");
            return;
        }
        if (member.priorEventId() != null && !epochsAdvanced) {
            store.setMemberStatus(member, "WAIT");
            return;
        }
        var authorization = consent.outboundAuthorization(member.actorGid(), actor.epoch(),
                member.recipientGid(), recipient.epoch());
        if (!authorization.allowed()) {
            // Keep the epochs that the previous event actually used. Once identity readiness arrives
            // in this same new epoch, the old event remains eligible for replacement.
            store.setMemberStatus(member, "WAIT");
            return;
        }
        commands.publish(member, new NotificationReadStore.Epoch(actor.userId(), actor.subjectId(), actor.epoch(),
                actor.status(), actor.identityReady()), new NotificationReadStore.Epoch(recipient.userId(),
                recipient.subjectId(), recipient.epoch(), recipient.status(), recipient.identityReady()));
    }

    private State consentState(UUID subjectId) {
        return subjectId == null ? null : consent.getBySubject(subjectId);
    }
}
