package io.metaloom.loom.rest.service.impl;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.VectorIndexOptions;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.vector.VectorIndexStatusResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.vector.EmbeddingIndexSyncService;

/**
 * Administrative routes for the embedding vector index.
 *
 * <p>
 * Two operations, both about the fact that the index is derived: {@code /rebuild} reconstructs it from the {@code embedding} table, and
 * {@code /status} reports which backend is bound and whether it is usable. Between them they are the whole answer to "we changed the embedding model"
 * and "we changed the index backend" - neither needs a data migration, because the vectors were never only in the index.
 * </p>
 *
 * <p>
 * <b>No silent degradation.</b> A rebuild against an unavailable index answers 503 naming the reason rather than reporting a successful rebuild of
 * nothing. {@code /status} is deliberately the exception: it answers 200 even when the index is off, because reporting that is its whole job.
 * </p>
 */
@Singleton
public class VectorIndexEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(VectorIndexEndpointService.class);

	private final EmbeddingIndexSyncService sync;
	private final VectorIndexOptions options;

	@Inject
	public VectorIndexEndpointService(EmbeddingIndexSyncService sync, VectorIndexOptions options, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.sync = sync;
		this.options = options;
	}

	/**
	 * {@code POST /api/v1/vector-index/rebuild} - drop and rebuild the index from the {@code embedding} table.
	 */
	public void rebuild(LoomRoutingContext lrc) {
		// Rebuilding walks every embedding and replaces the index; gate it on the strongest asset permission available.
		checkPerm(lrc, Permission.UPDATE_ASSET, () -> {
			requireAvailable();
			long indexed = sync.rebuild();
			log.info("Rebuilt the vector index from {} embedding(s) on request", indexed);
			lrc.send(status().setIndexed(indexed));
		});
	}

	/**
	 * {@code POST /api/v1/vector-index/sync} - drain the rows still marked dirty, without rebuilding.
	 *
	 * <p>
	 * The cheap counterpart to a rebuild: it touches only what the write hook missed. Useful after enabling a provider that was previously off, when
	 * every row written in the meantime is dirty but nothing else has changed.
	 * </p>
	 */
	public void sync(LoomRoutingContext lrc) {
		checkPerm(lrc, Permission.UPDATE_ASSET, () -> {
			requireAvailable();
			long indexed = sync.drain(options.getSyncBatchSize());
			lrc.send(status().setIndexed(indexed));
		});
	}

	/**
	 * {@code GET /api/v1/vector-index/status} - which backend is bound and whether it works.
	 */
	public void status(LoomRoutingContext lrc) {
		checkPerm(lrc, Permission.READ_ASSET, () -> lrc.send(status()));
	}

	private VectorIndexStatusResponse status() {
		return new VectorIndexStatusResponse()
			.setProvider(sync.index().providerName())
			.setAvailable(sync.index().isAvailable())
			.setEnabled(options.isEnabled());
	}

	private void requireAvailable() {
		if (!options.isEnabled() || !sync.index().isAvailable()) {
			throw new LoomRestException(503, LoomRestErrorCode.SEARCH_UNAVAILABLE,
				"The embedding vector index is unavailable"
					+ (options.isEnabled() ? " (the index could not be opened)." : " (LOOM_VECTOR_INDEX_PROVIDER=none)."));
		}
	}
}
