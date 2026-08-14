package io.metaloom.loom.api.options;

/**
 * Options which control the asset relationship graph index - the traversal structure behind "what else is like this asset".
 *
 * <p>
 * Like search, fingerprint similarity and the vector index, this is a capability rather than a dependency: when no provider is selected or the chosen
 * one cannot be opened, Loom still boots, every relation is still written to Postgres, and only the relatedness queries are unavailable. Because the
 * link tables are the system of record, turning a provider on later and rebuilding picks up everything written while it was off - running without one
 * costs query ability, never data.
 * </p>
 *
 * <p>
 * Disabled by default, and deliberately so. The backend it binds is single-writer and has no backup mechanism of its own; that is acceptable for a
 * rebuildable index and not for anything else, and the default should not quietly imply otherwise.
 * </p>
 */
public class AssetGraphOptions implements Option {

	/** No index. See {@code NoopAssetGraphIndex}. */
	public static final String PROVIDER_NONE = "none";

	/** On-disk graph store, {@code io.metaloom.graph:graph-storage-ffm-poc}. See {@code GraphStoreAssetGraphIndex}. */
	public static final String PROVIDER_GRAPHSTORE = "graphstore";

	public static final int DEFAULT_LIMIT = 50;

	public static final int DEFAULT_SYNC_BATCH_SIZE = 1000;

	@EnvironmentVariable(name = "LOOM_ASSET_GRAPH_PROVIDER", description = "Which asset graph index backend to bind: none or graphstore. The link tables are the system of record either way, so this can be changed and the index rebuilt without losing relationships.")
	private String provider = PROVIDER_NONE;

	@EnvironmentVariable(name = "LOOM_ASSET_GRAPH_PATH", description = "Directory holding the on-disk asset graph index. Rebuildable from the link tables at any time.")
	private String indexPath = "asset-graph-index";

	@EnvironmentVariable(name = "LOOM_ASSET_GRAPH_LIMIT", description = "Default number of related assets returned per query; overridable per request.")
	private int limit = DEFAULT_LIMIT;

	@EnvironmentVariable(name = "LOOM_ASSET_GRAPH_SYNC_BATCH_SIZE", description = "Maximum link rows projected into the index per batch during a rebuild. Each batch is one transaction, and a transaction is bounded by heap in this backend.")
	private int syncBatchSize = DEFAULT_SYNC_BATCH_SIZE;

	public String getProvider() {
		return provider;
	}

	public AssetGraphOptions setProvider(String provider) {
		this.provider = provider;
		return this;
	}

	public boolean isEnabled() {
		return provider != null && !PROVIDER_NONE.equalsIgnoreCase(provider);
	}

	public String getIndexPath() {
		return indexPath;
	}

	public AssetGraphOptions setIndexPath(String indexPath) {
		this.indexPath = indexPath;
		return this;
	}

	public int getLimit() {
		return limit;
	}

	public AssetGraphOptions setLimit(int limit) {
		this.limit = limit;
		return this;
	}

	public int getSyncBatchSize() {
		return syncBatchSize;
	}

	public AssetGraphOptions setSyncBatchSize(int syncBatchSize) {
		this.syncBatchSize = syncBatchSize;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		if (provider == null || provider.isBlank()) {
			errors.add("provider", "The asset graph index provider (LOOM_ASSET_GRAPH_PROVIDER) must be set; use 'none' to disable it.");
			return;
		}
		if (!PROVIDER_NONE.equalsIgnoreCase(provider) && !PROVIDER_GRAPHSTORE.equalsIgnoreCase(provider)) {
			// Named explicitly rather than silently falling back, so a typo in the provider name cannot
			// look like a working index that quietly answers nothing.
			errors.add("provider", "Unknown asset graph index provider '" + provider + "' (LOOM_ASSET_GRAPH_PROVIDER). Supported: "
				+ PROVIDER_NONE + ", " + PROVIDER_GRAPHSTORE + ".");
			return;
		}
		if (!isEnabled()) {
			return;
		}
		if (indexPath == null || indexPath.isBlank()) {
			errors.add("indexPath", "The asset graph index path (LOOM_ASSET_GRAPH_PATH) must be set when a provider is selected.");
		}
		if (limit <= 0) {
			errors.add("limit", "The asset graph query limit (LOOM_ASSET_GRAPH_LIMIT) must be positive.");
		}
		if (syncBatchSize <= 0) {
			errors.add("syncBatchSize", "The asset graph sync batch size (LOOM_ASSET_GRAPH_SYNC_BATCH_SIZE) must be positive.");
		}
	}
}
