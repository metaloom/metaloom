package io.metaloom.loom.db.jooq.search;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;

import io.metaloom.loom.api.search.IndexStatus;
import io.metaloom.loom.api.search.SearchDocument;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchIndexer;

/**
 * The indexer bound alongside the Postgres provider.
 *
 * <p>
 * It is intentionally a no-op: the Postgres index <i>is</i> the {@code search_document} table, and SQL triggers maintain it inside the same transaction
 * as the write that caused it. Triggers rather than DAO write hooks because {@code AbstractJooqDao.storeBatch} uses {@code batchInsert}, and the demo
 * initializer and Flyway backfills bypass the DAO layer entirely - triggers are the only layer that cannot be bypassed.
 * </p>
 *
 * <p>
 * {@link #status()} is real, because an operator still wants to know how big the index is.
 * </p>
 */
@Singleton
public class NoopSearchIndexer implements SearchIndexer {

	private final DSLContext ctx;

	@Inject
	public NoopSearchIndexer(DSLContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public void ensureSchema() {
		// Flyway owns the schema.
	}

	@Override
	public void index(SearchDocument doc) {
		// Triggers do this.
	}

	@Override
	public void indexBulk(List<SearchDocument> docs) {
		// Triggers do this.
	}

	@Override
	public void delete(SearchEntityType type, UUID entityUuid) {
		// Triggers do this.
	}

	@Override
	public void deleteByAsset(UUID assetUuid) {
		// The search_document.asset_uuid foreign key cascades.
	}

	@Override
	public IndexStatus status() {
		try {
			long count = ctx.fetchOne("SELECT count(*) AS c FROM search_document").get("c", Long.class);
			return new IndexStatus().setHealthy(true).setDocumentCount(count)
				.setDetail("Maintained synchronously by database triggers.");
		} catch (Exception e) {
			return new IndexStatus().setHealthy(false).setDetail(e.getMessage());
		}
	}

	/**
	 * Drop and rebuild every document from the source tables.
	 *
	 * <p>
	 * Delegates to the {@code search_document_rebuild()} SQL function, which calls the very same per-entity refresh functions the triggers call. That
	 * shared implementation is what makes a rebuild byte-identical to the incremental result, and it is the repair path when a trigger is ever found to
	 * have drifted.
	 * </p>
	 *
	 * @return the number of documents in the index afterwards
	 */
	public long rebuild() {
		return ctx.fetchOne("SELECT search_document_rebuild() AS c").get("c", Long.class);
	}
}
