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
import io.metaloom.loom.api.options.SimilarityOptions;
import io.metaloom.loom.api.search.SimilarityIndex;
import io.metaloom.loom.similarity.NoopSimilarityIndex;
import io.metaloom.loom.similarity.lucene.LuceneSimilarityIndex;

/**
 * Binds the fingerprint similarity index selected by {@code LOOM_SIMILARITY_ENABLED} (see spec/features/search/LUCENE_PLAN.md).
 *
 * <p>
 * 🔴 <b>Similarity must never fail server boot.</b> Like search, it is a capability, not a dependency: if the index directory is unusable or the
 * Lucene index cannot be opened, this module logs the failure and binds {@link NoopSimilarityIndex}. The similar-assets routes then reject requests
 * with a named reason while every other route keeps working - never a silent empty result, which would be indistinguishable from "no duplicates".
 * </p>
 *
 * <p>
 * The index is a <b>derived cache</b> of {@code asset_fingerprint_comp}; it is populated by the comp write hook and can be rebuilt in full at any time
 * via {@code POST /api/v1/similarity-index/rebuild}.
 * </p>
 */
@Module
public class SimilarityModule {

	private static final Logger log = LoggerFactory.getLogger(SimilarityModule.class);

	@Provides
	@Singleton
	public SimilarityOptions similarityOptions(LoomOptions options) {
		return options.getSimilarity();
	}

	@Provides
	@Singleton
	public SimilarityIndex similarityIndex(SimilarityOptions options) {
		if (!options.isEnabled()) {
			log.info("Fingerprint similarity is disabled (LOOM_SIMILARITY_ENABLED=false)");
			return new NoopSimilarityIndex();
		}
		try {
			Path indexPath = Paths.get(options.getIndexPath());
			Files.createDirectories(indexPath);
			if (!Files.isWritable(indexPath)) {
				// Boot guard: an unwritable index directory degrades to the Noop rather than killing the server.
				log.error("The similarity index path {} is not writable; fingerprint similarity will be unavailable", indexPath);
				return new NoopSimilarityIndex();
			}
			LuceneSimilarityIndex index = new LuceneSimilarityIndex(indexPath);
			if (!index.isAvailable()) {
				log.error("The similarity index at {} could not be opened; fingerprint similarity will be unavailable", indexPath);
				return new NoopSimilarityIndex();
			}
			log.info("Fingerprint similarity index ready at {} (algorithm {})", indexPath, options.getAlgorithm());
			return index;
		} catch (Exception e) {
			log.error("The similarity index failed to start; fingerprint similarity will be unavailable", e);
			return new NoopSimilarityIndex();
		}
	}
}
