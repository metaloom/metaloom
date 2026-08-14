package io.metaloom.loom.core.dagger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Module;
import dagger.Provides;
import io.metaloom.loom.api.graph.AssetGraphIndex;
import io.metaloom.loom.api.options.AssetGraphOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.graph.NoopAssetGraphIndex;
import io.metaloom.loom.graph.store.GraphStoreAssetGraphIndex;

/**
 * Binds the asset relationship graph index selected by {@code LOOM_ASSET_GRAPH_PROVIDER}.
 *
 * <p>
 * 🔴 <b>The asset graph index must never fail server boot.</b> Like search, fingerprint similarity and the vector index it is a capability, not a
 * dependency: if the index directory is unusable or the backend cannot be opened, this module logs the failure and binds
 * {@link NoopAssetGraphIndex}. The relatedness routes then reject requests with a named reason while every other route keeps working — never a silent
 * empty result, which would be indistinguishable from "nothing is related to this asset".
 * </p>
 *
 * <p>
 * The index is a <b>derived projection</b> of {@code tag_asset}, {@code collection_asset}, {@code remix_member} and the asset-to-person path through
 * {@code detection}. It can be rebuilt in full at any time. That is why swapping the backend, or adding a relation to the schema, is a configuration
 * change plus a rebuild rather than a data migration — and it is also why the backend's single-writer limitation is acceptable here and would not be
 * anywhere else.
 * </p>
 */
@Module
public class AssetGraphIndexModule {

	private static final Logger log = LoggerFactory.getLogger(AssetGraphIndexModule.class);

	@Provides
	@Singleton
	public AssetGraphOptions assetGraphOptions(LoomOptions options) {
		return options.getAssetGraph();
	}

	@Provides
	@Singleton
	public AssetGraphIndex assetGraphIndex(AssetGraphOptions options) {
		if (!options.isEnabled()) {
			log.info("The asset graph index is disabled (LOOM_ASSET_GRAPH_PROVIDER=none); relationships are still stored, only relatedness queries are off");
			return new NoopAssetGraphIndex();
		}
		if (!AssetGraphOptions.PROVIDER_GRAPHSTORE.equalsIgnoreCase(options.getProvider())) {
			// validate() rejects this at boot; reaching it means the options were built in code rather
			// than from the environment, so name the provider instead of pretending it works.
			log.error("Unknown asset graph index provider {}; relatedness queries will be unavailable", options.getProvider());
			return new NoopAssetGraphIndex();
		}
		try {
			Path indexPath = Paths.get(options.getIndexPath());
			Files.createDirectories(indexPath);
			if (!Files.isWritable(indexPath)) {
				// Boot guard: an unwritable index directory degrades to the Noop rather than killing the server.
				log.error("The asset graph index path {} is not writable; relatedness queries will be unavailable", indexPath);
				return new NoopAssetGraphIndex();
			}
			GraphStoreAssetGraphIndex index = new GraphStoreAssetGraphIndex(indexPath);
			if (!index.isAvailable()) {
				log.error("The asset graph index at {} could not be opened; relatedness queries will be unavailable", indexPath);
				return new NoopAssetGraphIndex();
			}
			log.info("Asset graph index ready at {} (provider {})", indexPath, options.getProvider());
			return index;
		} catch (Exception e) {
			log.error("The asset graph index failed to start; relatedness queries will be unavailable", e);
			return new NoopAssetGraphIndex();
		}
	}
}
