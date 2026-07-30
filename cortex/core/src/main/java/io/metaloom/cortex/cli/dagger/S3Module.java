package io.metaloom.cortex.cli.dagger;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Module;
import dagger.Provides;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.S3ClientOptions;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.media.MediaReferenceResolver;
import io.metaloom.cortex.s3.AwsS3ObjectStore;
import io.metaloom.cortex.s3.S3MediaMaterializer;
import io.metaloom.cortex.s3.S3MediaReferenceResolver;
import io.metaloom.cortex.s3.S3ObjectStore;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.cortex.s3.event.S3EventBuffer;
import io.metaloom.cortex.s3.event.SqsS3EventSource;
import io.metaloom.cortex.s3.event.WebhookS3EventSource;

/**
 * Wires S3 access for the worker.
 *
 * <p>Entirely optional: with no endpoint and no credentials configured, {@link S3Support} is
 * inactive, no SDK client is built, and the plain {@link MediaReferenceResolver} is provided - so
 * a worker that never touches object storage behaves exactly as it did before S3 existed.</p>
 *
 * <p>Note the resolver is wired for <em>every</em> worker, not only those whitelisted for
 * {@code s3-source}. Materialization is lazy and happens wherever a node task lands, so a
 * hash-only worker still has to be able to fetch {@code s3://} media - which is precisely what
 * removes the shared-storage requirement.</p>
 */
@Module
public abstract class S3Module {

	private static final Logger log = LoggerFactory.getLogger(S3Module.class);

	/** Directory under the meta path holding materialized objects. */
	public static final String CACHE_DIR = "s3_bin";

	/** Directory under the meta path holding the persisted per-bucket object indexes. */
	public static final String INDEX_DIR = "s3-index";

	@Provides
	@Singleton
	static S3ClientOptions provideS3Options(CortexOptions cortexOptions) {
		return cortexOptions.getS3() == null ? new S3ClientOptions() : cortexOptions.getS3();
	}

	@Provides
	@Singleton
	static S3Support provideS3Support(S3ClientOptions options, CortexOptions cortexOptions) {
		if (!isConfigured(options)) {
			log.debug("No S3 endpoint or credentials configured; S3 support stays inactive");
			return S3Support.inactive();
		}
		Path cachePath = resolveCachePath(options, cortexOptions);
		S3ObjectStore store = new AwsS3ObjectStore(options);
		S3MediaMaterializer materializer = new S3MediaMaterializer(store, cachePath,
			options.getMaxObjectSize(), options.getMaxCacheBytes());
		log.info("S3 media access is active; objects materialize into {}", cachePath);
		return S3Support.active(store, materializer, resolveIndexPath(options, cortexOptions));
	}

	/**
	 * The worker-wide change-hint buffer. Must be a singleton: transports fill it continuously
	 * while each run drains what it needs, so a per-node instance would silently lose hints.
	 */
	@Provides
	@Singleton
	static S3EventBuffer provideS3EventBuffer(S3ClientOptions options) {
		return new S3EventBuffer(options.getEvents().getMaxBufferedKeys());
	}

	/**
	 * The webhook receiver. Always provided; it registers no route unless webhook events are
	 * enabled and a shared secret is set, so the monitoring server can wire it unconditionally.
	 */
	@Provides
	@Singleton
	static WebhookS3EventSource provideWebhookS3EventSource(S3EventBuffer buffer, S3ClientOptions options) {
		return new WebhookS3EventSource(buffer, options.getEvents());
	}

	/**
	 * The SQS poller. Always provided; {@code start()} is a no-op unless SQS events are enabled
	 * and a queue URL is set.
	 */
	@Provides
	@Singleton
	static SqsS3EventSource provideSqsS3EventSource(S3EventBuffer buffer, S3ClientOptions options) {
		return new SqsS3EventSource(buffer, options);
	}

	@Provides
	@Singleton
	static MediaReferenceResolver provideMediaReferenceResolver(LoomMediaLoader mediaLoader, S3Support s3) {
		if (!s3.isActive()) {
			return new MediaReferenceResolver(mediaLoader);
		}
		return new S3MediaReferenceResolver(mediaLoader, s3.materializer());
	}

	/**
	 * S3 counts as configured only when a connection could actually be made. A region alone is not
	 * enough - it has a default - so requiring an endpoint or explicit credentials is what keeps a
	 * plain filesystem worker from building a pointless AWS client at boot.
	 */
	static boolean isConfigured(S3ClientOptions options) {
		if (options == null) {
			return false;
		}
		boolean hasEndpoint = options.getEndpoint() != null && !options.getEndpoint().isBlank();
		boolean hasCredentials = options.getAccessKey() != null && !options.getAccessKey().isBlank();
		return hasEndpoint || hasCredentials;
	}

	/**
	 * Explicit cache path wins, otherwise derive it from the meta path - the same convention the
	 * thumbnail, tts and imagegen nodes use for their {@code *_bin} directories.
	 */
	static Path resolveCachePath(S3ClientOptions options, CortexOptions cortexOptions) {
		if (options.getCachePath() != null && !options.getCachePath().isBlank()) {
			return Paths.get(options.getCachePath()).toAbsolutePath().normalize();
		}
		if (cortexOptions != null && cortexOptions.getMetaPath() != null) {
			return cortexOptions.getMetaPath().resolve(CACHE_DIR);
		}
		throw new IllegalStateException(
			"S3 is configured but neither an S3 cache path nor a meta path is set; "
				+ "set --s3-cache-path (CORTEX_S3_CACHE_PATH) or --meta-path (CORTEX_META_PATH)");
	}

	/**
	 * Explicit index path wins, otherwise derive it from the meta path. Mirrors how
	 * {@code filesystem-source} resolves its own index directory.
	 */
	static Path resolveIndexPath(S3ClientOptions options, CortexOptions cortexOptions) {
		if (options != null && options.getIndexPath() != null && !options.getIndexPath().isBlank()) {
			return Paths.get(options.getIndexPath()).toAbsolutePath().normalize();
		}
		if (cortexOptions != null && cortexOptions.getMetaPath() != null) {
			return cortexOptions.getMetaPath().resolve(INDEX_DIR);
		}
		return null;
	}
}
