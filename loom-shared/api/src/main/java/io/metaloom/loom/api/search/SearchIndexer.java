package io.metaloom.loom.api.search;

import java.util.List;
import java.util.UUID;

/**
 * Write side of search.
 *
 * <p>
 * The Postgres provider binds a <b>no-op</b> implementation: its index is the {@code search_document} table, which SQL triggers maintain inside the
 * same transaction as the write. Triggers rather than DAO hooks because {@code batchInsert}, the demo initializer and Flyway backfills all bypass the
 * DAO layer - triggers are the only layer that cannot be bypassed.
 * </p>
 *
 * <p>
 * An external index (Elasticsearch) binds a real implementation, fed from the {@code dirty} flag on the same table.
 * </p>
 */
public interface SearchIndexer {

	/** Create or migrate whatever the index needs. Idempotent. */
	void ensureSchema();

	void index(SearchDocument doc);

	void indexBulk(List<SearchDocument> docs);

	void delete(SearchEntityType type, UUID entityUuid);

	/** Remove every document derived from the given asset, including its transcript/segment/detection children. */
	void deleteByAsset(UUID assetUuid);

	IndexStatus status();
}
