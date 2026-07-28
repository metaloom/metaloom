package io.metaloom.cortex.s3;

import java.nio.file.Path;

/**
 * Whether this worker can talk to object storage, and the collaborators to do it with.
 *
 * <p>A single injectable value that is always present but may be <em>inactive</em>. Modelling it
 * this way rather than injecting nullable collaborators keeps the Dagger graph free of
 * {@code @Nullable} bindings, and gives callers one honest question to ask
 * ({@link #isActive()}) instead of several null checks that can disagree.</p>
 *
 * <p>Inactive is the normal state for a deployment that never touches S3: no SDK client is
 * built, no connection is opened at boot, and media resolution behaves exactly as it did before
 * S3 existed.</p>
 */
public final class S3Support {

	private static final S3Support INACTIVE = new S3Support(null, null, null);

	private final S3ObjectStore store;
	private final S3MediaMaterializer materializer;
	private final Path indexBaseDir;

	private S3Support(S3ObjectStore store, S3MediaMaterializer materializer, Path indexBaseDir) {
		this.store = store;
		this.materializer = materializer;
		this.indexBaseDir = indexBaseDir;
	}

	/**
	 * @return the shared "this worker has no S3 configuration" value
	 */
	public static S3Support inactive() {
		return INACTIVE;
	}

	/**
	 * @param store        the object store
	 * @param materializer the materializer backed by that store
	 * @param indexBaseDir directory for persisted per-bucket object indexes
	 * @return an active support value
	 */
	public static S3Support active(S3ObjectStore store, S3MediaMaterializer materializer, Path indexBaseDir) {
		if (store == null || materializer == null) {
			throw new IllegalArgumentException("An active S3Support needs both a store and a materializer");
		}
		return new S3Support(store, materializer, indexBaseDir);
	}

	/**
	 * @return true when S3 is configured on this worker
	 */
	public boolean isActive() {
		return store != null;
	}

	/**
	 * @return the object store
	 * @throws IllegalStateException when S3 is not configured, which is a configuration error the
	 *         caller should surface rather than a condition to work around
	 */
	public S3ObjectStore store() {
		requireActive();
		return store;
	}

	/**
	 * @return the materializer
	 * @throws IllegalStateException when S3 is not configured
	 */
	public S3MediaMaterializer materializer() {
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
			throw new IllegalStateException(
				"This worker has no S3 configuration. Set --s3-endpoint (CORTEX_S3_ENDPOINT) or "
					+ "--s3-access-key (CORTEX_S3_ACCESS_KEY) to enable S3 media access.");
		}
	}
}
