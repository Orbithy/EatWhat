package you.v50to.eatwhat.sync.catalog;

import you.v50to.eatwhat.account.ConsentStore;
import you.v50to.eatwhat.sync.SyncEvent;
import you.v50to.eatwhat.sync.transport.EventPolicy;
import you.v50to.eatwhat.sync.transport.NodeIdentity;

import java.util.Set;

/** Catalog transport gate; epoch checks happen at the final send authorization point. */
public final class CatalogEventPolicy implements EventPolicy {
    private final ConsentStore consent;
    private final Set<String> sites;
    private final java.util.function.Supplier<String> campusSiteId;

    public CatalogEventPolicy(ConsentStore consent, Set<String> sites) {
        this(consent, sites, null);
    }

    public CatalogEventPolicy(ConsentStore consent, Set<String> sites, String campusSiteId) {
        this(consent,()->campusSiteId,sites);
    }
    public CatalogEventPolicy(ConsentStore consent,java.util.function.Supplier<String> campusSiteId,Set<String> sites) {
        this.consent = consent;
        this.sites = you.v50to.eatwhat.cluster.ClusterMembershipView.freezeOrLive(sites);
        this.campusSiteId = java.util.Objects.requireNonNull(campusSiteId);
    }

    @Override public Set<String> aggregateTypes() { return Set.of(CatalogEvents.AGGREGATE); }
    @Override public java.util.Map<String,Set<Integer>> supportedSchemaVersions() { return java.util.Map.of(CatalogEvents.AGGREGATE,Set.of(1,2)); }

    @Override
    public void validate(SyncEvent event, NodeIdentity expectedOrigin) {
        if (!event.originSite().equals(expectedOrigin.siteId())
                || !event.originInstanceId().equals(expectedOrigin.instanceId())) {
            throw new IllegalArgumentException("CATALOG_ORIGIN_MISMATCH");
        }
        if(event.schemaVersion()==2) {
            CatalogMultiwriterEvents.evidence(event);
            CatalogEvents.validateEvent(CatalogMultiwriterEvents.legacyShape(event),expectedOrigin.siteId(),sites,true);
        } else {
            CatalogEvents.validateEvent(event, expectedOrigin.siteId(), sites);
            CatalogEvents.validateAuthoritySite(event, campusSiteId.get());
        }
    }

    @Override
    public OutboundDecision outbound(SyncEvent event) {
        String authority=campusSiteId.get(); // An unavailable membership proof is retryable, not a policy denial.
        try {
            boolean authenticatedMultiwriter=event.schemaVersion()==2;
            if(authenticatedMultiwriter) { CatalogMultiwriterEvents.evidence(event); event=CatalogMultiwriterEvents.legacyShape(event); }
            else CatalogEvents.validateAuthoritySite(event, authority);
            if (CatalogEvents.ACTION.equals(event.eventType())) {
                CatalogEvents.Action action = CatalogEvents.parseAction(event.payloadJson());
                return decision(action.consentEpochs(),authenticatedMultiwriter && action.operation()==CatalogOperation.DELETE);
            }
            if (CatalogEvents.BRIDGE.equals(event.eventType())) {
                CatalogEvents.StateBridge bridge = CatalogEvents.parseBridge(event.payloadJson());
                return decision(bridge.consentEpochs(),false);
            }
            return OutboundDecision.POLICY_BLOCKED;
        } catch (RuntimeException invalid) {
            return OutboundDecision.POLICY_BLOCKED;
        }
    }

    private OutboundDecision decision(java.util.List<CatalogEvents.ConsentEpoch> epochs,boolean removal) {
        if (epochs.isEmpty()) return OutboundDecision.ALLOW;
        boolean waiting = false;
        for (CatalogEvents.ConsentEpoch epoch : epochs) {
            // A later ban must not strand a previously committed deletion. The receiver still
            // verifies its immutable actor witness; current consent and exact epochs remain mandatory.
            ConsentStore.OutboundAuthorization authorization = removal
                    ? consent.outboundRemovalAuthorization(epoch.subjectGid(),epoch.epoch(),epoch.subjectGid(),epoch.epoch())
                    : consent.outboundAuthorization(epoch.subjectGid(),epoch.epoch(),epoch.subjectGid(),epoch.epoch());
            if (authorization.blocked()) return OutboundDecision.POLICY_BLOCKED;
            if (!authorization.allowed()) waiting = true;
        }
        return waiting ? OutboundDecision.WAIT : OutboundDecision.ALLOW;
    }
}
