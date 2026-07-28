package io.metaloom.cortex.s3;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.option.S3ClientOptions;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * {@link S3ObjectStore} over the AWS SDK v2, usable against real S3 and any S3-compatible
 * gateway (MinIO, Ceph).
 *
 * <p>The HTTP client is pinned to {@link UrlConnectionHttpClient} rather than resolved through
 * the SDK's {@code ServiceLoader} chain. Two reasons: the workload is a handful of large
 * sequential transfers rather than high-concurrency small requests, so Netty buys nothing; and
 * the cortex CLI is shaded, where an unpinned chain is fragile - see
 * {@code spec/features/pipeline-nodes/NODE_S3SOURCE_PLAN.md} section 9.</p>
 */
public class AwsS3ObjectStore implements S3ObjectStore {

	private static final Logger log = LoggerFactory.getLogger(AwsS3ObjectStore.class);

	private static final int PAGE_SIZE = 1000;

	private final S3Client client;

	public AwsS3ObjectStore(S3ClientOptions options) {
		this(buildClient(options));
	}

	AwsS3ObjectStore(S3Client client) {
		this.client = client;
	}

	private static S3Client buildClient(S3ClientOptions options) {
		var builder = S3Client.builder()
			.httpClientBuilder(UrlConnectionHttpClient.builder()
				.socketTimeout(Duration.ofMinutes(5))
				.connectionTimeout(Duration.ofSeconds(30)))
			.region(Region.of(options.getRegion() == null || options.getRegion().isBlank()
				? S3ClientOptions.DEFAULT_REGION
				: options.getRegion()));

		if (options.getEndpoint() != null && !options.getEndpoint().isBlank()) {
			builder.endpointOverride(URI.create(options.getEndpoint()));
		}
		builder.forcePathStyle(options.isPathStyleAccess());

		String accessKey = options.getAccessKey();
		String secretKey = options.getSecretKey();
		if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(
				AwsBasicCredentials.create(accessKey, secretKey)));
		} else {
			// Env vars, profile, container/instance role, IRSA - whatever the deployment provides.
			builder.credentialsProvider(DefaultCredentialsProvider.create());
		}

		log.info("Building S3 client (endpoint={}, region={}, pathStyle={})",
			options.getEndpoint() == null ? "<aws>" : options.getEndpoint(),
			options.getRegion(), options.isPathStyleAccess());
		return builder.build();
	}

	@Override
	public S3Listing list(String bucket, String prefix, String continuationToken, String startAfter) throws IOException {
		ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
			.bucket(bucket)
			.maxKeys(PAGE_SIZE);
		if (prefix != null && !prefix.isBlank()) {
			request.prefix(prefix);
		}
		if (continuationToken != null && !continuationToken.isBlank()) {
			request.continuationToken(continuationToken);
		} else if (startAfter != null && !startAfter.isBlank()) {
			// S3 ignores startAfter once a continuation token is present, hence the else.
			request.startAfter(startAfter);
		}

		try {
			ListObjectsV2Response response = client.listObjectsV2(request.build());
			List<S3ObjectRef> objects = new ArrayList<>(response.contents().size());
			for (S3Object object : response.contents()) {
				// A "directory placeholder" is a zero-byte object whose key ends in a slash.
				// It is not media and must never reach the pipeline.
				if (object.key().endsWith("/")) {
					continue;
				}
				objects.add(new S3ObjectRef(bucket, object.key(), object.eTag(),
					object.size() == null ? -1 : object.size(),
					object.lastModified() == null ? 0 : object.lastModified().toEpochMilli()));
			}
			return new S3Listing(objects, Boolean.TRUE.equals(response.isTruncated())
				? response.nextContinuationToken()
				: null);
		} catch (SdkException e) {
			throw new IOException("Failed to list s3://" + bucket + "/" + (prefix == null ? "" : prefix), e);
		}
	}

	@Override
	public S3ObjectRef head(String bucket, String key) throws IOException {
		try {
			HeadObjectResponse response = client.headObject(HeadObjectRequest.builder()
				.bucket(bucket).key(key).build());
			return new S3ObjectRef(bucket, key, response.eTag(),
				response.contentLength() == null ? -1 : response.contentLength(),
				response.lastModified() == null ? 0 : response.lastModified().toEpochMilli());
		} catch (NoSuchKeyException e) {
			return null;
		} catch (SdkException e) {
			// A 404 on HEAD surfaces as a generic S3Exception rather than NoSuchKeyException on
			// some gateways, so treat a not-found status as "gone" rather than an error.
			if (isNotFound(e)) {
				return null;
			}
			throw new IOException("Failed to head s3://" + bucket + "/" + key, e);
		}
	}

	@Override
	public void download(String bucket, String key, Path target) throws IOException {
		// ResponseTransformer.toFile refuses to write over an existing file, so clear any
		// leftover from an interrupted earlier attempt rather than failing on it.
		Files.deleteIfExists(target);
		try {
			client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), target);
		} catch (SdkException e) {
			throw new IOException("Failed to download s3://" + bucket + "/" + key, e);
		}
	}

	@Override
	public S3ObjectRef upload(String bucket, String key, Path source, String contentType) throws IOException {
		if (!Files.isRegularFile(source)) {
			throw new IOException("Cannot upload " + source + ": not a regular file");
		}
		long size = Files.size(source);
		PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(key);
		if (contentType != null && !contentType.isBlank()) {
			request.contentType(contentType);
		}
		try {
			PutObjectResponse response = client.putObject(request.build(), RequestBody.fromFile(source));
			// The etag comes from the response rather than being computed locally: for a multipart
			// upload it is an md5-of-md5s, which no local hash would reproduce.
			return new S3ObjectRef(bucket, key, response.eTag(), size, System.currentTimeMillis());
		} catch (SdkException e) {
			throw new IOException("Failed to upload " + source + " to s3://" + bucket + "/" + key, e);
		}
	}

	private static boolean isNotFound(SdkException e) {
		if (e instanceof software.amazon.awssdk.services.s3.model.S3Exception s3e) {
			return s3e.statusCode() == 404;
		}
		return false;
	}

	@Override
	public void close() {
		client.close();
	}
}
