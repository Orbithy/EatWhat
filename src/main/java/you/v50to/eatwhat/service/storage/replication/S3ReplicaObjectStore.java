package you.v50to.eatwhat.service.storage.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * S3-compatible object store for replication.
 * Supports RustFS, Wasabi, and other S3-like endpoints.
 * Uses bounded-memory streaming with SHA-256 digest computation.
 */
@Slf4j
public class S3ReplicaObjectStore implements DeletableObjectStore, AutoCloseable {
    private static final int BUFFER_SIZE = 64 * 1024; // 64 KiB buffers

    /** S3 minimum size of every part except the last. */
    public static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    /** S3 limit on the number of parts of one multipart upload. */
    public static final int MAX_PARTS = 10_000;
    /** Defaults mirror FileReplicationProperties. */
    public static final long DEFAULT_MULTIPART_THRESHOLD = 16L * 1024 * 1024;
    public static final long DEFAULT_PART_SIZE = 8L * 1024 * 1024;

    private static final int LIST_PAGE = 1000;
    private static final int LIST_MAX_PAGES = 1000;

    /**
     * Multipart and throttling settings.
     *
     * @param multipartThreshold files of at least this many bytes are uploaded with multipart; smaller ones use one PUT
     * @param partSize size of every part but the last (5 MiB minimum); the last part carries the remainder
     * @param limiter byte-rate throttle applied to downloads and uploads of this store
     */
    public record Tuning(long multipartThreshold, long partSize, ByteRateLimiter limiter) {
        public Tuning {
            if (partSize < MIN_PART_SIZE) {
                throw new IllegalArgumentException("partSize must be at least 5 MiB");
            }
            if (multipartThreshold < 1) {
                throw new IllegalArgumentException("multipartThreshold must be positive");
            }
            Objects.requireNonNull(limiter, "limiter");
        }

        public static Tuning defaults() {
            return new Tuning(DEFAULT_MULTIPART_THRESHOLD, DEFAULT_PART_SIZE, ByteRateLimiter.unlimited());
        }
    }

    private final S3Client client;
    private final String bucket;
    private final long multipartThreshold;
    private final long partSize;
    private final ByteRateLimiter limiter;

    /**
     * Create a store for the given endpoint and credentials.
     *
     * @param endpoint S3-compatible endpoint URL
     * @param region AWS region or equivalent
     * @param bucket bucket name
     * @param accessKey access key ID
     * @param secretKey secret access key
     * @param pathStyle use path-style URLs (true) vs virtual-hosted (false)
     */
    public S3ReplicaObjectStore(String endpoint, String region, String bucket,
                               String accessKey, String secretKey, boolean pathStyle) {
        this(endpoint, region, bucket, accessKey, secretKey, pathStyle, Tuning.defaults());
    }

    /**
     * Same as the six-argument constructor with explicit multipart and throttle settings.
     *
     * @param tuning multipart threshold, part size and byte-rate limiter
     */
    public S3ReplicaObjectStore(String endpoint, String region, String bucket,
                               String accessKey, String secretKey, boolean pathStyle, Tuning tuning) {
        Objects.requireNonNull(tuning, "tuning");
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(region) ||
            !StringUtils.hasText(bucket) || !StringUtils.hasText(accessKey) ||
            !StringUtils.hasText(secretKey)) {
            throw new IllegalArgumentException("All S3 configuration parameters must be non-empty");
        }

        this.bucket = bucket;
        this.multipartThreshold = tuning.multipartThreshold();
        this.partSize = tuning.partSize();
        this.limiter = tuning.limiter();
        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build())
                .build();
    }

    @Override
    public Optional<ObjectMeta> head(String key) {
        try {
            HeadObjectResponse response = client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build());
            return Optional.of(new ObjectMeta(
                    response.contentLength() == null ? 0L : response.contentLength(),
                    response.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public DigestResult downloadTo(String key, Path tmp, long maxBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long totalBytes = 0;

            try (var response = client.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build());
                 var output = Files.newOutputStream(tmp)) {

                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = response.read(buffer)) >= 0) {
                    totalBytes += count;
                    if (totalBytes > maxBytes) {
                        throw new ObjectTooLargeException(maxBytes, totalBytes);
                    }
                    limiter.acquire(count);
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }

            String sha256Hex = HexFormat.of().formatHex(digest.digest());
            return new DigestResult(totalBytes, sha256Hex);
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(key);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to download and verify object: " + key, e);
        }
    }

    @Override
    public void putFile(String key, String contentType, Path src, String sha256Hex) {
        putFile(key, contentType, src, sha256Hex, UploadListener.NONE);
    }

    /**
     * Upload a file without ever overwriting. Below {@code multipartThreshold} this is the original single PUT; from
     * the threshold up it is a multipart upload (create, fixed-size parts read straight from the file, complete).
     *
     * <p>Memory stays bounded by the 64 KiB copy buffers regardless of the file size: parts are streamed from a shared
     * {@link FileChannel} through {@link BoundedFileInputStream}. Any failure (SDK error, interrupt, a listener
     * throwing {@link LeaseLostException}, the destination appearing meanwhile) aborts the multipart upload this call
     * started and rethrows. The multipart ETag is never used as an integrity signal: the caller's full SHA-256
     * readback is the integrity check. The no-overwrite guarantee is a HEAD before and again right before the
     * completion (not atomic; a concurrent writer of the same immutable key can still win the race, which the
     * caller's readback detects).
     */
    @Override
    public void putFile(String key, String contentType, Path src, String sha256Hex, UploadListener listener) {
        Objects.requireNonNull(listener, "listener");
        final long size;
        try {
            size = Files.size(src);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean multipart = size >= multipartThreshold;
        long partCount = multipart ? (size + partSize - 1) / partSize : 1;
        if (partCount > MAX_PARTS) {
            throw new IllegalArgumentException("A " + size + " byte file needs " + partCount + " parts of " + partSize
                    + " bytes, above the " + MAX_PARTS + " part limit; raise the part size");
        }

        // Check if object already exists and refuse overwrite.
        // Uses head-check approach: first verify key does not exist, then upload.
        // If object exists, immediately fails without attempting upload (safe and idempotent).
        Optional<ObjectMeta> existing = head(key);
        if (existing.isPresent()) {
            log.warn("Object already exists at key: {}, refusing to overwrite", key);
            throw new IllegalStateException("Object already exists at key: " + key +
                    ". Replication must not overwrite existing objects.");
        }

        if (!multipart) {
            limiter.acquire(size);
            // Upload with SHA-256 in user metadata
            PutObjectResponse response = client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .metadata(java.util.Map.of("sha256", sha256Hex))
                            .build(),
                    RequestBody.fromFile(src));

            log.debug("Successfully uploaded object: {} (ETag: {})", key, response.eTag());
            return;
        }
        putMultipart(key, contentType, src, sha256Hex, size, (int) partCount, listener);
    }

    private void putMultipart(String key, String contentType, Path src, String sha256Hex, long size, int partCount,
                              UploadListener listener) {
        String uploadId = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .metadata(java.util.Map.of("sha256", sha256Hex))
                .build()).uploadId();
        boolean completed = false;
        try (FileChannel channel = FileChannel.open(src, StandardOpenOption.READ)) {
            listener.onMultipartStarted(uploadId);
            List<CompletedPart> parts = new ArrayList<>(partCount);
            for (int number = 1; number <= partCount; number++) {
                final long offset = (long) (number - 1) * partSize;
                final long length = Math.min(partSize, size - offset);
                limiter.acquire(length);
                // A provider, not a one-shot stream: the SDK may re-read the part (payload signing, retries) and every
                // new stream is another bounded view of the file, never a copy in memory.
                ContentStreamProvider provider = () -> new BoundedFileInputStream(channel, offset, length);
                UploadPartResponse response = client.uploadPart(UploadPartRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(number)
                                .contentLength(length)
                                .build(),
                        RequestBody.fromContentProvider(provider, length, "application/octet-stream"));
                parts.add(CompletedPart.builder().partNumber(number).eTag(response.eTag()).build());
                listener.onPartUploaded(number, partCount);
            }
            if (head(key).isPresent()) {
                log.warn("Object appeared at key {} while its multipart upload was in progress; aborting", key);
                throw new IllegalStateException("Object already exists at key: " + key +
                        ". Replication must not overwrite existing objects.");
            }
            listener.onBeforeComplete();
            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
            completed = true;
            log.debug("Successfully uploaded object: {} ({} parts)", key, partCount);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (!completed) {
                abortOwnUpload(key, uploadId);
            }
        }
    }

    /**
     * Best-effort cleanup of the multipart upload this very call started. An interrupt flag is cleared while aborting
     * (a set flag would make the request fail immediately) and restored afterwards. A failure is only logged: the
     * stale-multipart sweep removes what is left.
     */
    private void abortOwnUpload(String key, String uploadId) {
        boolean interrupted = Thread.interrupted();
        try {
            abortMultipartUpload(key, uploadId);
        } catch (RuntimeException abortFailure) {
            log.warn("Could not abort multipart upload {} of {}; the stale-multipart sweep will remove it", uploadId, key,
                    abortFailure);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public DigestResult readbackDigest(String key, long maxBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long totalBytes = 0;

            try (var response = client.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build())) {

                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = response.read(buffer)) >= 0) {
                    totalBytes += count;
                    if (totalBytes > maxBytes) {
                        throw new ObjectTooLargeException(maxBytes, totalBytes);
                    }
                    limiter.acquire(count);
                    digest.update(buffer, 0, count);
                }
            }

            String sha256Hex = HexFormat.of().formatHex(digest.digest());
            return new DigestResult(totalBytes, sha256Hex);
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(key);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to verify object: " + key, e);
        }
    }

    @Override
    public ObjectPage listObjects(String prefix, String continuationToken, int maxKeys) {
        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .maxKeys(maxKeys);
        if (prefix != null && !prefix.isEmpty()) request.prefix(prefix);
        if (continuationToken != null && !continuationToken.isEmpty()) request.continuationToken(continuationToken);
        ListObjectsV2Response response = client.listObjectsV2(request.build());
        List<ObjectEntry> entries = response.contents().stream()
                .map(o -> new ObjectEntry(o.key(), o.size() == null ? 0L : o.size(), o.lastModified()))
                .toList();
        String next = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        return new ObjectPage(entries, next);
    }

    @Override
    public List<MultipartUploadEntry> listMultipartUploads(String prefix) {
        List<MultipartUploadEntry> uploads = new ArrayList<>();
        String keyMarker = null;
        String uploadIdMarker = null;
        for (int page = 0; page < LIST_MAX_PAGES; page++) {
            ListMultipartUploadsRequest.Builder request = ListMultipartUploadsRequest.builder()
                    .bucket(bucket)
                    .maxUploads(LIST_PAGE);
            if (prefix != null && !prefix.isEmpty()) request.prefix(prefix);
            if (keyMarker != null) request.keyMarker(keyMarker);
            if (uploadIdMarker != null) request.uploadIdMarker(uploadIdMarker);
            ListMultipartUploadsResponse response = client.listMultipartUploads(request.build());
            response.uploads().forEach(u -> uploads.add(new MultipartUploadEntry(u.key(), u.uploadId(), u.initiated())));
            if (!Boolean.TRUE.equals(response.isTruncated())) {
                return uploads;
            }
            String nextKey = response.nextKeyMarker();
            String nextUpload = response.nextUploadIdMarker();
            if (java.util.Objects.equals(nextKey, keyMarker) && java.util.Objects.equals(nextUpload, uploadIdMarker)) {
                break; // a store that does not advance its markers must not make this loop forever
            }
            keyMarker = nextKey;
            uploadIdMarker = nextUpload;
        }
        log.warn("Stopped listing multipart uploads under '{}' after {} entries", prefix, uploads.size());
        return uploads;
    }

    @Override
    public void abortMultipartUpload(String key, String uploadId) {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(uploadId)) {
            throw new IllegalArgumentException("Object key and upload id must not be blank");
        }
        try {
            client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    // Request-level bound: the sweep must not hang on one call.
                    .overrideConfiguration(o -> o
                            .apiCallTimeout(java.time.Duration.ofSeconds(30))
                            .apiCallAttemptTimeout(java.time.Duration.ofSeconds(15)))
                    .build());
        } catch (NoSuchUploadException alreadyGone) {
            // Completed, or aborted by someone else: the goal state already holds.
            log.debug("Multipart upload {} of {} no longer exists", uploadId, key);
        }
    }

    @Override
    public void deleteObject(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("Object key must not be blank");
        }
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    // Request-level bound only for deletes: a hung call must not hold the GC's row locks forever.
                    .overrideConfiguration(o -> o
                            .apiCallTimeout(java.time.Duration.ofSeconds(30))
                            .apiCallAttemptTimeout(java.time.Duration.ofSeconds(15)))
                    .build());
        } catch (NoSuchKeyException alreadyAbsent) {
            // S3 DeleteObject is idempotent; tolerate stores that report an absent key.
            log.debug("Object already absent at key: {}", key);
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
