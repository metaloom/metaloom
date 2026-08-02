package io.metaloom.cortex.cloud;

/**
 * The cloud drives cortex can ingest from.
 *
 * <p>Each provider gets its own node kind rather than one generic {@code cloud-source} with a
 * provider parameter, and that is not cosmetic: {@code RegistryNodeRegistrar} advertises a kind
 * only when the worker actually holds credentials for it, so Loom can refuse a run up front
 * instead of dispatching one that dies mid-flight. A single kind could not express "this worker
 * can serve Google but not Microsoft".</p>
 */
public enum CloudProviderId {

	GDRIVE("gdrive", "gdrive-source", "Google Drive"),

	ONEDRIVE("onedrive", "onedrive-source", "OneDrive");

	private final String scheme;
	private final String kind;
	private final String displayName;

	CloudProviderId(String scheme, String kind, String displayName) {
		this.scheme = scheme;
		this.kind = kind;
		this.displayName = displayName;
	}

	/**
	 * @return the URI scheme of this provider's media references, without {@code ://}
	 */
	public String scheme() {
		return scheme;
	}

	/**
	 * @return the pipeline node kind, which is also the node options {@code KEY} and the
	 *         descriptor kind - all four strings must match
	 */
	public String kind() {
		return kind;
	}

	/**
	 * @return a human-readable provider name for log messages
	 */
	public String displayName() {
		return displayName;
	}

	/**
	 * @return the directory under the meta path holding materialized files
	 */
	public String cacheDir() {
		return scheme + "_bin";
	}

	/**
	 * @return the directory under the meta path holding persisted scan indexes
	 */
	public String indexDir() {
		return scheme + "-index";
	}

	/**
	 * @param scheme a URI scheme, without {@code ://}
	 * @return the matching provider, or null
	 */
	public static CloudProviderId forScheme(String scheme) {
		for (CloudProviderId provider : values()) {
			if (provider.scheme.equals(scheme)) {
				return provider;
			}
		}
		return null;
	}

	/**
	 * @param kind a node kind
	 * @return the matching provider, or null
	 */
	public static CloudProviderId forKind(String kind) {
		for (CloudProviderId provider : values()) {
			if (provider.kind.equals(kind)) {
				return provider;
			}
		}
		return null;
	}
}
