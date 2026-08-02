package io.metaloom.cortex.cloud;

import java.nio.file.Path;

/**
 * Whether this worker can talk to one cloud provider, and the collaborators to do it with.
 *
 * <p>Always present but possibly <em>inactive</em>, the same shape {@code S3Support} uses and for
 * the same reason: modelling "not configured" as a value rather than a nullable binding keeps the
 * Dagger graph free of {@code @Nullable} and gives callers one honest question to ask instead of
 * several null checks that can disagree.</p>
 */
public final class CloudSupport {

	private final CloudProviderId provider;
	private final CloudFileStore store;
	private final CloudMediaMaterializer materializer;
	private final Path indexBaseDir;

	private CloudSupport(CloudProviderId provider, CloudFileStore store, CloudMediaMaterializer materializer,
		Path indexBaseDir) {
		this.provider = provider;
		this.store = store;
		this.materializer = materializer;
		this.indexBaseDir = indexBaseDir;
	}

	/**
	 * @param provider the provider that is not configured
	 * @return a "this worker has no credentials for that cloud" value
	 */
	public static CloudSupport inactive(CloudProviderId provider) {
		return new CloudSupport(provider, null, null, null);
	}

	/**
	 * @param provider     the provider
	 * @param store        the file store
	 * @param materializer the materializer backed by that store
	 * @param indexBaseDir directory for persisted scan indexes
	 * @return an active support value
	 */
	public static CloudSupport active(CloudProviderId provider, CloudFileStore store,
		CloudMediaMaterializer materializer, Path indexBaseDir) {
		if (provider == null) {
			throw new IllegalArgumentException("A provider must be given");
		}
		if (store == null || materializer == null) {
			throw new IllegalArgumentException("An active CloudSupport needs both a store and a materializer");
		}
		return new CloudSupport(provider, store, materializer, indexBaseDir);
	}

	public CloudProviderId provider() {
		return provider;
	}

	/**
	 * @return true when this provider is configured on this worker
	 */
	public boolean isActive() {
		return store != null;
	}

	/**
	 * @return the file store
	 * @throws IllegalStateException when the provider is not configured, which is a configuration
	 *         error the caller should surface rather than work around
	 */
	public CloudFileStore store() {
		requireActive();
		return store;
	}

	/**
	 * @return the materializer
	 * @throws IllegalStateException when the provider is not configured
	 */
	public CloudMediaMaterializer materializer() {
		requireActive();
		return materializer;
	}

	/**
	 * @return the index base directory, or null when none could be derived
	 */
	public Path indexBaseDir() {
		return indexBaseDir;
	}

	private void requireActive() {
		if (store == null) {
			throw new IllegalStateException("This worker has no " + provider.displayName() + " configuration. "
				+ (provider == CloudProviderId.GDRIVE
					? "Set --gdrive-service-account-json (CORTEX_GDRIVE_SERVICE_ACCOUNT_JSON) to enable it."
					: "Set --onedrive-tenant-id, --onedrive-client-id and --onedrive-client-secret to enable it."));
		}
	}
}
