package io.metaloom.loom.storage.s3;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.S3Options;
import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.loom.storage.BinaryStorageException;
import io.metaloom.loom.storage.StorageKeys;
import io.metaloom.utils.hash.SHA512;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link BinaryStorage} over one S3 bucket, usable against real S3 and any S3-compatible gateway (MinIO, Ceph, Garage).
 *
 * <p>
 * One instance per {@code asset_pool} row, held by {@code BinaryStorageResolver}; the underlying {@link S3Client} is expensive to build and is reused
 * for the lifetime of the pool.
 * </p>
 *
 * <p>
 * Locators are {@code s3://bucket/ab/cd/ef/<sha512>} — see {@link S3Locator} for why the scheme matters beyond this class.
 * </p>
 */
public class S3BinaryStorage implements BinaryStorage, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(S3BinaryStorage.class);

	public static final String KIND = "s3";

	private final S3Client client;
	private final String bucket;
	private final String describe;

	/**
	 * @param bucket
	 *            target bucket, from {@code asset_pool.s3_bucket}
	 * @param poolRegion
	 *            region from {@code asset_pool.s3_region}, may be null to fall back to the process default
	 * @param poolEndpoint
	 *            endpoint from {@code asset_pool.s3_endpoint}, may be null to fall back to the process default
	 * @param options
	 *            process-wide credentials and defaults
	 */
	public S3BinaryStorage(String bucket, String poolRegion, String poolEndpoint, S3Options options) {
		this(bucket, buildClient(poolRegion, poolEndpoint, options), describe(bucket, poolRegion, poolEndpoint, options));
	}

	/** Test seam: inject a client (or a MinIO-backed one) directly. */
	public S3BinaryStorage(String bucket, S3Client client, String describe) {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalArgumentException("An S3 binary storage needs a bucket");
		}
		this.bucket = bucket;
		this.client = client;
		this.describe = describe;
	}

	private static String describe(String bucket, String poolRegion, String poolEndpoint, S3Options options) {
		String endpoint = firstNonBlank(poolEndpoint, options.getEndpoint());
		return KIND + ":" + bucket + "@" + (endpoint == null ? "aws" : endpoint);
	}

	private static S3Client buildClient(String poolRegion, String poolEndpoint, S3Options options) {
		String region = firstNonBlank(poolRegion, options.getRegion(), S3Options.DEFAULT_REGION);
		String endpoint = firstNonBlank(poolEndpoint, options.getEndpoint());

		var builder = S3Client.builder()
			.httpClientBuilder(UrlConnectionHttpClient.builder()
				.socketTimeout(Duration.ofMinutes(5))
				.connectionTimeout(Duration.ofSeconds(30)))
			.region(Region.of(region));

		if (endpoint != null) {
			builder.endpointOverride(URI.create(endpoint));
		}
		builder.forcePathStyle(options.isPathStyleAccess(poolEndpoint));

		String accessKey = options.getAccessKey();
		String secretKey = options.getSecretKey();
		if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
		} else {
			// Env vars, profile, container/instance role, IRSA - whatever the deployment provides.
			builder.credentialsProvider(DefaultCredentialsProvider.create());
		}

		log.info("Building S3 client for bucket {} (endpoint={}, region={}, pathStyle={})",
			bucketLabel(endpoint), endpoint == null ? "<aws>" : endpoint, region, options.isPathStyleAccess(poolEndpoint));
		return builder.build();
	}

	private static String bucketLabel(String endpoint) {
		return endpoint == null ? "<aws>" : endpoint;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	@Override
	public String kind() {
		return KIND;
	}

	@Override
	public String locatorFor(SHA512 sha512) {
		return new S3Locator(bucket, StorageKeys.contentKey(sha512)).toReference();
	}

	@Override
	public String store(Path source, SHA512 sha512, String mimeType) {
		String key = StorageKeys.contentKey(sha512);
		S3Locator locator = new S3Locator(bucket, key);
		try {
			// Content-addressed: the same hash is the same bytes, so re-uploading is pure cost. The
			// head is one cheap round trip against an upload that is usually megabytes.
			if (headOrNull(key) != null) {
				log.debug("Binary {} already present in {}, reusing", sha512, bucket);
				return locator.toReference();
			}
			PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(key);
			if (mimeType != null && !mimeType.isBlank()) {
				request.contentType(mimeType);
			}
			client.putObject(request.build(), RequestBody.fromFile(source));
			return locator.toReference();
		} catch (SdkException e) {
			throw new BinaryStorageException("Could not store binary " + sha512 + " in " + describe, e);
		}
	}

	@Override
	public boolean exists(String locator) {
		return headOrNull(keyOf(locator)) != null;
	}

	@Override
	public long size(String locator) {
		HeadObjectResponse head = headOrNull(keyOf(locator));
		return head == null || head.contentLength() == null ? -1 : head.contentLength();
	}

	@Override
	public InputStream read(String locator, long offset, long length) {
		String key = keyOf(locator);
		GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucketOf(locator)).key(key);
		if (offset > 0 || length >= 0) {
			// HTTP range semantics: inclusive end. length < 0 means "to the end", which is an open range.
			String range = length < 0
				? "bytes=" + offset + "-"
				: "bytes=" + offset + "-" + (offset + length - 1);
			request.range(range);
		}
		try {
			return client.getObject(request.build());
		} catch (NoSuchKeyException e) {
			throw new BinaryStorageException("No object at " + locator, e);
		} catch (SdkException e) {
			throw new BinaryStorageException("Could not read " + locator + " from " + describe, e);
		}
	}

	@Override
	public Optional<Path> localPath(String locator) {
		// There is no local file. The download route streams read() instead of using sendFile.
		return Optional.empty();
	}

	@Override
	public void delete(String locator) {
		try {
			client.deleteObject(DeleteObjectRequest.builder().bucket(bucketOf(locator)).key(keyOf(locator)).build());
		} catch (NoSuchKeyException e) {
			// Already gone is the state the caller wanted.
			log.debug("Object {} was already absent", locator);
		} catch (SdkException e) {
			throw new BinaryStorageException("Could not delete " + locator + " from " + describe, e);
		}
	}

	@Override
	public Long freeSpace() {
		// A bucket has no capacity to report, and pretending otherwise (returning Long.MAX_VALUE)
		// would make the caller's "is there room" check silently meaningless. Null says "cannot say".
		return null;
	}

	@Override
	public String describe() {
		return describe;
	}

	@Override
	public void close() {
		client.close();
	}

	private HeadObjectResponse headOrNull(String key) {
		try {
			return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
		} catch (NoSuchKeyException e) {
			return null;
		} catch (S3Exception e) {
			// A HEAD on a missing key answers 404 without a typed exception on some gateways.
			if (e.statusCode() == 404) {
				return null;
			}
			throw new BinaryStorageException("Could not stat " + key + " in " + describe, e);
		} catch (SdkException e) {
			throw new BinaryStorageException("Could not stat " + key + " in " + describe, e);
		}
	}

	/**
	 * Locators carry their own bucket, so a pool that was re-pointed at a different bucket can still serve rows written before the change.
	 */
	private String bucketOf(String locator) {
		return S3Locator.isS3(locator) ? S3Locator.parse(locator).bucket() : bucket;
	}

	private String keyOf(String locator) {
		return S3Locator.isS3(locator) ? S3Locator.parse(locator).key() : locator;
	}
}
