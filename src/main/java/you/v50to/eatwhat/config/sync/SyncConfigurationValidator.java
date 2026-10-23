package you.v50to.eatwhat.config.sync;

import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import you.v50to.eatwhat.config.SiteProperties;

/** Validate domain consent gates, member routing and legacy transport bounds before connections. */
public final class SyncConfigurationValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Binder binder = Binder.get(context.getEnvironment());
        boolean activity = validateActivity(binder);
        boolean relation = validateRelation(binder);
        boolean profile = validateProfile(binder);
        boolean privacy = validatePrivacy(binder);
        boolean notification = validateNotification(binder);
        boolean catalog = validateCatalog(binder);
        boolean files = validateFiles(binder);
        boolean browse = validateDomain(binder, "browse", you.v50to.eatwhat.sync.browse.BrowseSyncProperties.class,
                you.v50to.eatwhat.sync.browse.BrowseSyncProperties::new);
        boolean preferences = validateDomain(binder, "preferences", you.v50to.eatwhat.sync.preferences.PreferenceSyncProperties.class,
                you.v50to.eatwhat.sync.preferences.PreferenceSyncProperties::new);
        try{
            boolean clustered=binder.bind("app.cluster.enabled",Boolean.class).orElse(false);
            var identity=binder.bind("app.site",SiteProperties.class).orElseGet(SiteProperties::new);
            if(clustered&&!you.v50to.eatwhat.cluster.ClusterRouting.configured(identity))throw new IllegalArgumentException();
            if(!clustered&&identity.getClusterId()!=null&&(activity||relation||profile||privacy||notification||catalog||files||browse||preferences||binder.bind("app.sync.transport-enabled",Boolean.class).orElse(false)))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new IllegalStateException("Cluster synchronization requires matching app.cluster.enabled and a valid bound site allocation");}
        for (String flag : new String[]{"app.sync.capture-enabled", "app.sync.transport-enabled"}) {
            boolean enabled;
            try {
                enabled = binder.bind(flag, Boolean.class).orElse(false);
            } catch (BindException failure) {
                throw new IllegalStateException(flag + " must be a boolean; event capture and transport remain disabled");
            }
            if (enabled && flag.equals("app.sync.capture-enabled")) {
                throw new IllegalStateException(flag + " cannot be enabled before domain and consent integration");
            }
            if (enabled) {
                try {
                    var properties = binder.bind("app.sync", SyncTransportProperties.class).orElseGet(SyncTransportProperties::new);
                    var site = binder.bind("app.site", SiteProperties.class).orElseGet(SiteProperties::new);
                    properties.validate(site, activity || relation || profile || privacy || notification || catalog || files || browse || preferences);
                } catch (RuntimeException failure) {
                    // Bind errors can contain raw store passwords/URLs. Do not chain or echo them.
                    throw new IllegalStateException("app.sync.transport-enabled requires valid synthetic-only, site, peer and TLS settings");
                }
            }
        }
    }

    private static boolean validateFiles(Binder binder) {
        try {
            var p=binder.bind("app.sync.files",you.v50to.eatwhat.sync.file.FileSyncProperties.class).orElseGet(you.v50to.eatwhat.sync.file.FileSyncProperties::new);
            if(!p.isEnabled()) return false;
            p.validate(binder.bind("app.site",SiteProperties.class).orElseGet(SiteProperties::new),
                binder.bind("app.sync",SyncTransportProperties.class).orElseGet(SyncTransportProperties::new),
                binder.bind("app.storage.files.replication.enabled",Boolean.class).orElse(false));
            if(!binder.bind("app.account.enabled",Boolean.class).orElse(false)) throw new IllegalArgumentException();
            return true;
        } catch(RuntimeException failure) {
            throw new IllegalStateException("app.sync.files.enabled requires explicit site, account consent, file replication and non-synthetic transport");
        }
    }

    /** Domain capture needs consent and explicit identity; a fixed peer is only for bilateral mode. */
    private static boolean validateActivity(Binder binder) {
        boolean enabled;
        try {
            enabled = binder.bind("app.sync.activity.enabled", Boolean.class).orElse(false);
        } catch (BindException failure) {
            throw new IllegalStateException("app.sync.activity.enabled must be a boolean");
        }
        if (!enabled) return false;
        try {
            var site = binder.bind("app.site", SiteProperties.class).orElseGet(SiteProperties::new);
            var properties = binder.bind("app.sync.activity", you.v50to.eatwhat.sync.activity.ActivitySyncProperties.class)
                    .orElseGet(you.v50to.eatwhat.sync.activity.ActivitySyncProperties::new);
            properties.validate(site);
            if (!binder.bind("app.account.enabled", Boolean.class).orElse(false)) {
                throw new IllegalArgumentException("consent module required");
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("app.sync.activity.enabled requires an explicit site, a remote peer site id and app.account.enabled=true");
        }
        return true;
    }
    private static boolean validateRelation(Binder binder) {
        try {
            boolean enabled=binder.bind("app.sync.relation.enabled",Boolean.class).orElse(false);
            if(!enabled) return false;
            var site=binder.bind("app.site",SiteProperties.class).orElseGet(SiteProperties::new);
            var p=binder.bind("app.sync.relation",you.v50to.eatwhat.sync.relation.RelationSyncProperties.class)
                .orElseGet(you.v50to.eatwhat.sync.relation.RelationSyncProperties::new);
            p.validate(site);
            if(!binder.bind("app.account.enabled",Boolean.class).orElse(false)) throw new IllegalArgumentException();
            String peer=binder.bind("app.sync.peer-site-id",String.class).orElse(p.getPeerSiteId());
            if(!you.v50to.eatwhat.cluster.ClusterRouting.configured(site)&&!java.util.Objects.equals(peer,p.getPeerSiteId())) throw new IllegalArgumentException();
            return true;
        } catch(RuntimeException failure) {
            throw new IllegalStateException("app.sync.relation.enabled requires an explicit site, matching remote peer and app.account.enabled=true");
        }
    }

    private static boolean validateProfile(Binder binder) {
        return validateDomain(binder, "profile", you.v50to.eatwhat.sync.profile.ProfileSyncProperties.class,
                you.v50to.eatwhat.sync.profile.ProfileSyncProperties::new);
    }

    private static boolean validatePrivacy(Binder binder) {
        return validateDomain(binder, "privacy", you.v50to.eatwhat.sync.privacy.PrivacySyncProperties.class,
                you.v50to.eatwhat.sync.privacy.PrivacySyncProperties::new);
    }

    private static boolean validateNotification(Binder binder) {
        boolean enabled;
        try { enabled = binder.bind("app.sync.notification.enabled", Boolean.class).orElse(false); }
        catch (BindException failure) { throw new IllegalStateException("app.sync.notification.enabled must be a boolean"); }
        if (!enabled) return false;
        try {
            var site = binder.bind("app.site", SiteProperties.class).orElseGet(SiteProperties::new);
            String peer = binder.bind("app.sync.peer-site-id", String.class).orElse(null);
            var properties = binder.bind("app.sync.notification",
                    you.v50to.eatwhat.sync.notification.NotificationReadSyncProperties.class)
                    .orElseGet(you.v50to.eatwhat.sync.notification.NotificationReadSyncProperties::new);
            properties.validate(site);
            if (!you.v50to.eatwhat.cluster.ClusterRouting.configured(site)&&!java.util.Objects.equals(peer, properties.getPeerSiteId())) throw new IllegalArgumentException();
            if (!binder.bind("app.account.enabled", Boolean.class).orElse(false)) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("app.sync.notification.enabled requires an explicit site, matching remote peer and app.account.enabled=true");
        }
        return true;
    }

    private static boolean validateCatalog(Binder binder) {
        boolean enabled;
        try { enabled = binder.bind("app.sync.catalog.enabled", Boolean.class).orElse(false); }
        catch (BindException failure) { throw new IllegalStateException("app.sync.catalog.enabled must be a boolean"); }
        if (!enabled) return false;
        try {
            var site = binder.bind("app.site", SiteProperties.class).orElseGet(SiteProperties::new);
            String peer = binder.bind("app.sync.peer-site-id", String.class).orElse(null);
            var properties = binder.bind("app.sync.catalog", you.v50to.eatwhat.sync.catalog.CatalogSyncProperties.class)
                    .orElseGet(you.v50to.eatwhat.sync.catalog.CatalogSyncProperties::new);
            properties.validate(site, peer);
            if (!binder.bind("app.account.enabled", Boolean.class).orElse(false)) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("app.sync.catalog.enabled requires explicit site identity, matching peer and app.account.enabled=true");
        }
        return true;
    }

    private static <T> boolean validateDomain(Binder binder, String name, Class<T> type,
                                               java.util.function.Supplier<T> defaults) {
        boolean enabled;
        try { enabled = binder.bind("app.sync." + name + ".enabled", Boolean.class).orElse(false); }
        catch (BindException failure) { throw new IllegalStateException("app.sync." + name + ".enabled must be a boolean"); }
        if (!enabled) return false;
        try {
            var site = binder.bind("app.site", SiteProperties.class).orElseGet(SiteProperties::new);
            String peer = binder.bind("app.sync.peer-site-id", String.class).orElse(null);
            Object properties = binder.bind("app.sync." + name, type).orElseGet(defaults);
            if (properties instanceof you.v50to.eatwhat.sync.profile.ProfileSyncProperties p) p.validate(site, peer);
            else if (properties instanceof you.v50to.eatwhat.sync.privacy.PrivacySyncProperties p) p.validate(site, peer);
            else if (properties instanceof you.v50to.eatwhat.sync.preferences.PreferenceSyncProperties p) {
                p.validate(site, peer);
                p.validatePolicy(binder.bind("app.account.policy-version",String.class).orElse("cross-border-v1"));
            }
            else if (properties instanceof you.v50to.eatwhat.sync.browse.BrowseSyncProperties p) {
                p.validate(site, peer);
                p.validatePolicy(binder.bind("app.account.policy-version",String.class).orElse("cross-border-v1"));
            }
            if (!binder.bind("app.account.enabled", Boolean.class).orElse(false)) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("app.sync." + name + ".enabled requires an explicit site, matching remote peer and app.account.enabled=true");
        }
        return true;
    }

}
