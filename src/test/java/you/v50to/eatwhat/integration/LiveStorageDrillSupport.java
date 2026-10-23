package you.v50to.eatwhat.integration;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import you.v50to.eatwhat.service.storage.replication.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Test-only configuration and exact-key capability boundary; never reads application.yml. */
final class LiveStorageDrillSupport {
    private LiveStorageDrillSupport() { }

    static Properties read(Path file) throws IOException {
        var p = new Properties();
        try (var reader = Files.newBufferedReader(file)) { p.load(reader); }
        return p;
    }

    static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank() || value.contains("REPLACE_") || value.contains("${")) {
            throw new IllegalArgumentException("Missing acceptance configuration: " + key);
        }
        return value.trim();
    }

    static void validate(Properties p, boolean localContract) {
        if (!"true".equals(p.getProperty("acceptance.dedicated-test-buckets"))) {
            throw new IllegalArgumentException("Dedicated test buckets must be explicitly selected");
        }
        for (String name : List.of("rustfs", "r2")) {
            URI uri = URI.create(required(p, name + ".endpoint"));
            boolean loopback = Set.of("127.0.0.1", "localhost").contains(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                         && (name.equals("rustfs") || localContract && loopback))
                    || uri.getHost().endsWith(".invalid")) {
                throw new IllegalArgumentException("Invalid acceptance endpoint: " + name);
            }
            if (!required(p, name + ".bucket").matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
                throw new IllegalArgumentException("Invalid acceptance bucket: " + name);
            }
            required(p, name + ".region");
            if (!Set.of("true", "false").contains(required(p, name + ".path-style"))) {
                throw new IllegalArgumentException("Explicit path-style setting required: " + name);
            }
            String file = p.getProperty(name + ".credentials-file");
            String profile = p.getProperty(name + ".credentials-profile");
            if ((file == null) == (profile == null)) {
                throw new IllegalArgumentException("Select exactly one credential source: " + name);
            }
            if (file != null && (!Path.of(file).isAbsolute() || !Files.isRegularFile(Path.of(file)))) {
                throw new IllegalArgumentException("Missing credentials file: " + name);
            }
        }
        if (!localContract && (!URI.create(p.getProperty("r2.endpoint")).getHost().matches(
                "[0-9a-f]{32}(?:\\.(?:eu|us|fedramp))?\\.r2\\.cloudflarestorage\\.com")
                || !"auto".equals(p.getProperty("r2.region"))
                || !Set.of(-1, 443).contains(URI.create(p.getProperty("r2.endpoint")).getPort()))) {
            throw new IllegalArgumentException("Real R2 account API endpoint and auto region required");
        }
        if (!localContract && URI.create(p.getProperty("rustfs.endpoint")).getHost().endsWith(".r2.cloudflarestorage.com")) {
            throw new IllegalArgumentException("RustFS must not use the R2 API endpoint");
        }
        if (p.getProperty("rustfs.endpoint").equals(p.getProperty("r2.endpoint"))
                && p.getProperty("rustfs.bucket").equals(p.getProperty("r2.bucket"))) {
            throw new IllegalArgumentException("The two sites must not use the same bucket");
        }
    }

    static S3ReplicaObjectStore open(Properties p, String name) throws IOException {
        String access, secret;
        if (p.getProperty(name + ".credentials-file") != null) {
            var credentials = read(Path.of(p.getProperty(name + ".credentials-file")));
            access = required(credentials, "access-key");
            secret = required(credentials, "secret-key");
        } else {
            try (var provider = ProfileCredentialsProvider.create(required(p, name + ".credentials-profile"))) {
                AwsCredentials credentials = provider.resolveCredentials();
                if (credentials instanceof AwsSessionCredentials) {
                    throw new IllegalArgumentException("This production adapter requires basic S3 credentials");
                }
                access = credentials.accessKeyId();
                secret = credentials.secretAccessKey();
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Acceptance credential profile could not be resolved: " + name);
            }
        }
        return new S3ReplicaObjectStore(required(p, name + ".endpoint"), required(p, name + ".region"),
                required(p, name + ".bucket"), access, secret,
                Boolean.parseBoolean(required(p, name + ".path-style")));
    }

    /** Prevents all listing and all access to keys outside this fixture's persisted ownership list. */
    static final class OwnedStore implements DeletableObjectStore, AutoCloseable {
        final S3ReplicaObjectStore delegate;
        final Set<String> keys;
        final AtomicInteger deletes = new AtomicInteger();
        boolean loseNextDeleteResponse;
        boolean blockConfirmationOnLostDelete;
        boolean headsUnavailable;

        OwnedStore(S3ReplicaObjectStore delegate, Set<String> keys) {
            this.delegate = delegate;
            this.keys = Set.copyOf(keys);
            if (keys.isEmpty() || keys.stream().anyMatch(k -> !k.matches("verified/[0-9a-f-]{36}"))) {
                throw new IllegalArgumentException("Only explicit verified UUID keys are accepted");
            }
        }

        private void allowed(String key) {
            if (!keys.contains(key)) throw new IllegalArgumentException("ACCEPTANCE_KEY_NOT_OWNED");
        }

        private <T> T request(String key, Supplier<T> call) {
            allowed(key);
            try { return call.get(); }
            catch (ObjectNotFoundException | ObjectTooLargeException | LeaseLostException expected) { throw expected; }
            catch (RuntimeException failure) {
                // SDK messages can include endpoints. Do not leak credentials or access-bearing URLs into evidence.
                throw new IllegalStateException("ACCEPTANCE_STORAGE_" + failure.getClass().getSimpleName());
            }
        }

        @Override public Optional<ObjectMeta> head(String key) {
            allowed(key);
            if (headsUnavailable) throw new IllegalStateException("ACCEPTANCE_HEAD_UNAVAILABLE_UNTIL_RESTART");
            return request(key, () -> delegate.head(key));
        }
        @Override public DigestResult readbackDigest(String key, long max) {
            return request(key, () -> delegate.readbackDigest(key, max));
        }
        @Override public DigestResult downloadTo(String key, Path target, long max) {
            return request(key, () -> delegate.downloadTo(key, target, max));
        }
        @Override public void putFile(String key, String type, Path source, String sha) {
            request(key, () -> { delegate.putFile(key, type, source, sha); return null; });
        }
        @Override public void putFile(String key, String type, Path source, String sha, UploadListener listener) {
            request(key, () -> { delegate.putFile(key, type, source, sha, listener); return null; });
        }
        @Override public void deleteObject(String key) {
            request(key, () -> { deletes.incrementAndGet(); delegate.deleteObject(key); return null; });
            if (loseNextDeleteResponse) {
                loseNextDeleteResponse = false;
                headsUnavailable = blockConfirmationOnLostDelete;
                throw new IllegalStateException("ACCEPTANCE_DELETE_RESPONSE_LOST");
            }
        }
        @Override public ObjectPage listObjects(String prefix, String token, int max) {
            throw new UnsupportedOperationException("ACCEPTANCE_BUCKET_LIST_DISABLED");
        }
        @Override public List<MultipartUploadEntry> listMultipartUploads(String prefix) {
            throw new UnsupportedOperationException("ACCEPTANCE_MULTIPART_LIST_DISABLED");
        }
        @Override public void abortMultipartUpload(String key, String uploadId) {
            throw new UnsupportedOperationException("ACCEPTANCE_MULTIPART_ABORT_DISABLED");
        }
        @Override public void close() { delegate.close(); }
    }
}
