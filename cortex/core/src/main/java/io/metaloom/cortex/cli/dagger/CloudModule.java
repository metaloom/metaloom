package io.metaloom.cortex.cli.dagger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Module;
import dagger.Provides;
import io.metaloom.cortex.api.option.CloudClientOptions;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.metaloom.cortex.api.option.OneDriveClientOptions;
import io.metaloom.cortex.cloud.CloudFileStore;
import io.metaloom.cortex.cloud.CloudMediaMaterializer;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.CloudSupport;
import io.metaloom.cortex.cloud.CloudSupportRegistry;
import io.metaloom.cortex.cloud.auth.CloudTokenSource;
import io.metaloom.cortex.cloud.auth.GoogleRefreshTokenSource;
import io.metaloom.cortex.cloud.auth.GoogleServiceAccountTokenSource;
import io.metaloom.cortex.cloud.auth.MicrosoftClientCredentialsTokenSource;
import io.metaloom.cortex.cloud.auth.MicrosoftRefreshTokenSource;
import io.metaloom.cortex.cloud.gdrive.GoogleDriveFileStore;
import io.metaloom.cortex.cloud.graph.GraphFileStore;

/**
 * Wires cloud-drive access for the worker.
 *
 * <p>Entirely optional and per provider: with no credentials configured the corresponding
 * {@link CloudSupport} is inactive, no client is built, and nothing about the worker changes. A
 * worker may perfectly well have Google credentials and not Microsoft, which is exactly why the
 * kinds are registered separately.</p>
 *
 * <p>Like {@code S3Module}, the collaborators here are needed by <em>every</em> worker that touches
 * cloud media, not only the one running a source node: materialization is lazy and happens wherever
 * a node task lands.</p>
 */
@Module
public abstract class CloudModule {

	private static final Logger log = LoggerFactory.getLogger(CloudModule.class);

	@Provides
	@Singleton
	static GDriveClientOptions provideGdriveOptions(CortexOptions cortexOptions) {
		return cortexOptions.getGdrive() == null ? new GDriveClientOptions() : cortexOptions.getGdrive();
	}

	@Provides
	@Singleton
	static OneDriveClientOptions provideOnedriveOptions(CortexOptions cortexOptions) {
		return cortexOptions.getOnedrive() == null ? new OneDriveClientOptions() : cortexOptions.getOnedrive();
	}

	@Provides
	@Singleton
	static CloudSupportRegistry provideCloudSupport(GDriveClientOptions gdrive, OneDriveClientOptions onedrive,
		CortexOptions cortexOptions) {

		Map<CloudProviderId, CloudSupport> supports = new EnumMap<>(CloudProviderId.class);
		supports.put(CloudProviderId.GDRIVE, buildGdrive(gdrive, cortexOptions));
		supports.put(CloudProviderId.ONEDRIVE, buildOnedrive(onedrive, cortexOptions));
		return new CloudSupportRegistry(supports);
	}

	static CloudSupport buildGdrive(GDriveClientOptions options, CortexOptions cortexOptions) {
		requireCoherent(CloudProviderId.GDRIVE, options);
		if (!options.isConfigured()) {
			log.debug("No Google Drive credentials configured; Google Drive support stays inactive");
			return CloudSupport.inactive(CloudProviderId.GDRIVE);
		}
		try {
			CloudTokenSource tokenSource = options.hasServiceAccount()
				? new GoogleServiceAccountTokenSource(options, Clock.systemUTC())
				: new GoogleRefreshTokenSource(options, Clock.systemUTC());
			CloudFileStore store = new GoogleDriveFileStore(options, tokenSource);
			return activate(CloudProviderId.GDRIVE, store, options, cortexOptions);
		} catch (IOException e) {
			// A malformed service-account key is a deployment error, not a missing capability:
			// staying quietly inactive would surface later as an unexplained "unsupported kind".
			throw new IllegalStateException("Google Drive is configured but its credentials could not be "
				+ "loaded: " + e.getMessage(), e);
		}
	}

	static CloudSupport buildOnedrive(OneDriveClientOptions options, CortexOptions cortexOptions) {
		requireCoherent(CloudProviderId.ONEDRIVE, options);
		if (!options.isConfigured()) {
			log.debug("No OneDrive credentials configured; OneDrive support stays inactive");
			return CloudSupport.inactive(CloudProviderId.ONEDRIVE);
		}
		CloudTokenSource tokenSource = options.hasRefreshTokenGrant()
			? new MicrosoftRefreshTokenSource(options, Clock.systemUTC())
			: new MicrosoftClientCredentialsTokenSource(options, Clock.systemUTC());
		CloudFileStore store = new GraphFileStore(options, tokenSource);
		return activate(CloudProviderId.ONEDRIVE, store, options, cortexOptions);
	}

	private static CloudSupport activate(CloudProviderId provider, CloudFileStore store,
		CloudClientOptions<?> options, CortexOptions cortexOptions) {

		Path cachePath = resolveCachePath(provider, options, cortexOptions);
		CloudMediaMaterializer materializer = new CloudMediaMaterializer(store, cachePath,
			options.getMaxObjectSize(), options.getMaxCacheBytes());
		log.info("{} media access is active; files materialize into {}", provider.displayName(), cachePath);
		return CloudSupport.active(provider, store, materializer, resolveIndexPath(provider, options, cortexOptions));
	}

	/**
	 * Refuse to boot on a half-filled credential set.
	 *
	 * <p>Treating "client id but no secret" as "not configured" would turn a typo into a missing
	 * capability, and Loom would then reject runs with an unhelpful "no worker supports this kind"
	 * long after the mistake was made.</p>
	 */
	private static void requireCoherent(CloudProviderId provider, CloudClientOptions<?> options) {
		String reason = options.partialConfigurationReason();
		if (reason != null) {
			throw new IllegalStateException(reason);
		}
	}

	/**
	 * Explicit cache path wins, otherwise derive it from the meta path - the same convention the
	 * S3, thumbnail, tts and imagegen paths use.
	 */
	static Path resolveCachePath(CloudProviderId provider, CloudClientOptions<?> options, CortexOptions cortexOptions) {
		if (options.getCachePath() != null && !options.getCachePath().isBlank()) {
			return Paths.get(options.getCachePath()).toAbsolutePath().normalize();
		}
		if (cortexOptions != null && cortexOptions.getMetaPath() != null) {
			return cortexOptions.getMetaPath().resolve(provider.cacheDir());
		}
		throw new IllegalStateException(provider.displayName() + " is configured but neither a cache path nor a "
			+ "meta path is set; set --" + provider.scheme() + "-cache-path (CORTEX_"
			+ provider.scheme().toUpperCase() + "_CACHE_PATH) or --meta-path (CORTEX_META_PATH)");
	}

	/**
	 * Explicit index path wins, otherwise derive it from the meta path. Mirrors how the other
	 * differential sources resolve their index directory.
	 */
	static Path resolveIndexPath(CloudProviderId provider, CloudClientOptions<?> options, CortexOptions cortexOptions) {
		if (options != null && options.getIndexPath() != null && !options.getIndexPath().isBlank()) {
			return Paths.get(options.getIndexPath()).toAbsolutePath().normalize();
		}
		if (cortexOptions != null && cortexOptions.getMetaPath() != null) {
			return cortexOptions.getMetaPath().resolve(provider.indexDir());
		}
		return null;
	}
}
