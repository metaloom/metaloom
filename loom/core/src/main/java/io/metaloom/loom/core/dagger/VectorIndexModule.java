package io.metaloom.loom.core.dagger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Module;
import dagger.Provides;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.VectorIndexOptions;
import io.metaloom.loom.api.search.VectorIndex;
import io.metaloom.loom.vector.NoopVectorIndex;
import io.metaloom.loom.vector.lucene.LuceneVectorIndex;

/**
 * Binds the embedding vector index selected by {@code LOOM_VECTOR_INDEX_PROVIDER}.
 *
 * <p>
 * 🔴 <b>The vector index must never fail server boot.</b> Like search and fingerprint similarity it is a capability, not a dependency: if the index
 * directory is unusable or the backend cannot be opened, this module logs the failure and binds {@link NoopVectorIndex}. The similarity routes then
 * reject requests with a named reason while every other route keeps working - never a silent empty result, which would be indistinguishable from "this
 * face matches nobody".
 * </p>
 *
 * <p>
 * The index is a <b>derived cache</b> of {@code embedding.vector}. It is populated by the endpoint write hook and by {@code EmbeddingSyncService}
 * draining rows still marked {@code dirty}, and can be rebuilt in full at any time via {@code POST /api/v1/vector-index/rebuild}. That is why swapping
 * the backend, or the embedding model, is a configuration change plus a rebuild rather than a data migration.
 * </p>
 */
@Module
public class VectorIndexModule {

	private static final Logger log = LoggerFactory.getLogger(VectorIndexModule.class);

	@Provides
	@Singleton
	public VectorIndexOptions vectorIndexOptions(LoomOptions options) {
		return options.getVectorIndex();
	}

	@Provides
	@Singleton
	public VectorIndex vectorIndex(VectorIndexOptions options) {
		if (!options.isEnabled()) {
			log.info("The vector index is disabled (LOOM_VECTOR_INDEX_PROVIDER=none); embeddings are still stored, only similarity queries are off");
			return new NoopVectorIndex();
		}
		if (!VectorIndexOptions.PROVIDER_LUCENE.equalsIgnoreCase(options.getProvider())) {
			// validate() rejects this at boot; reaching it means the options were built in code rather
			// than from the environment, so name the provider instead of pretending it works.
			log.error("Unknown vector index provider {}; vector queries will be unavailable", options.getProvider());
			return new NoopVectorIndex();
		}
		try {
			Path indexPath = Paths.get(options.getIndexPath());
			Files.createDirectories(indexPath);
			if (!Files.isWritable(indexPath)) {
				// Boot guard: an unwritable index directory degrades to the Noop rather than killing the server.
				log.error("The vector index path {} is not writable; vector queries will be unavailable", indexPath);
				return new NoopVectorIndex();
			}
			LuceneVectorIndex index = new LuceneVectorIndex(indexPath);
			if (!index.isAvailable()) {
				log.error("The vector index at {} could not be opened; vector queries will be unavailable", indexPath);
				return new NoopVectorIndex();
			}
			log.info("Vector index ready at {} (provider {})", indexPath, options.getProvider());
			return index;
		} catch (Exception e) {
			log.error("The vector index failed to start; vector queries will be unavailable", e);
			return new NoopVectorIndex();
		}
	}
}
