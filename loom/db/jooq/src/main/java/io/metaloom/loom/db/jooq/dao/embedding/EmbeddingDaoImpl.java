package io.metaloom.loom.db.jooq.dao.embedding;

import static io.metaloom.loom.db.jooq.tables.JooqEmbedding.EMBEDDING;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqEmbedding;
import io.metaloom.loom.db.model.asset.AssetComponent;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;

@Singleton
public class EmbeddingDaoImpl extends AbstractJooqDao<Embedding> implements EmbeddingDao {

	@Inject
	public EmbeddingDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Embeddings";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqEmbedding.EMBEDDING;
	}

	@Override
	protected Class<? extends Embedding> getPojoClass() {
		return EmbeddingImpl.class;
	}

	@Override
	public Embedding createEmbedding(UUID userUuid, UUID assetUuid, Float[] vector, String type) {
		Embedding embedding = new EmbeddingImpl();
		embedding.setVector(vector);
		embedding.setType(type);
		embedding.setAssetUuid(assetUuid);
		// Embeddings created through the API rather than by a node are attributed to the user.
		// A node overrides this with its own kind before storing.
		embedding.setNodeKind(AssetComponent.NODE_KIND_MANUAL);
		setCreatorEditor(embedding, userUuid);
		return embedding;
	}

	@Override
	public void store(Embedding element) {
		prepare(element);
		TableRecord<?> reco = ctx().newRecord(getTable(), element);
		if (element.getUuid() == null) {
			reco.reset("uuid");
		}
		UUID uuid = ctx().insertInto(getTable())
			.set(reco)
			.returning(getTable().field("uuid", UUID.class))
			.fetchOne("uuid", UUID.class);
		if (uuid == null) {
			throw new RuntimeException("Key null!!");
		}
		element.setUuid(uuid);
	}

	@Override
	public Embedding upsertEmbedding(Embedding element) {
		prepare(element);
		// Idempotent on the (asset_uuid, node_kind, type, model, frame_number, subject_index) unique key.
		// model is part of that key since V2.75, so re-running a node under a NEW model adds a row beside
		// the old one instead of overwriting it - two models can be compared before either is dropped.
		upsert(element, EMBEDDING.ASSET_UUID, EMBEDDING.NODE_KIND, EMBEDDING.TYPE, EMBEDDING.MODEL, EMBEDDING.FRAME_NUMBER,
			EMBEDDING.SUBJECT_INDEX);
		return element;
	}

	@Override
	public List<Embedding> findDirty(int limit) {
		return ctx().selectFrom(EMBEDDING)
			.where(EMBEDDING.DIRTY.isTrue())
			.orderBy(EMBEDDING.SYNCED_AT.asc())
			.limit(limit)
			.fetchInto(EmbeddingImpl.class)
			.stream()
			.map(e -> (Embedding) e)
			.toList();
	}

	@Override
	public Stream<Embedding> streamAll() {
		return ctx().selectFrom(EMBEDDING)
			.orderBy(EMBEDDING.UUID.asc())
			.fetchStreamInto(EmbeddingImpl.class)
			.map(e -> (Embedding) e);
	}

	@Override
	public void markSynced(Collection<UUID> uuids) {
		if (uuids == null || uuids.isEmpty()) {
			return;
		}
		ctx().update(EMBEDDING)
			.set(EMBEDDING.DIRTY, Boolean.FALSE)
			.set(EMBEDDING.SYNCED_AT, LocalDateTime.now())
			.where(EMBEDDING.UUID.in(uuids))
			.execute();
	}

	/**
	 * Fill in what the caller may legitimately have left blank, so a write never has to carry boilerplate.
	 *
	 * <p>
	 * {@code dimensions} is derived from the vector rather than trusted, because the V2.75 CHECK rejects a row whose two disagree - deriving it here
	 * turns a class of constraint violations into something that cannot happen. The index-bookkeeping columns get the DDL defaults so a producer that
	 * knows nothing about the vector index still writes a row the sync can pick up.
	 * </p>
	 */
	private void prepare(Embedding element) {
		if (element.getDimensions() == null && element.getVector() != null) {
			element.setDimensions(element.getVector().length);
		}
		if (element.getModel() == null) {
			element.setModel("");
		}
		if (element.getDirty() == null) {
			element.setDirty(Boolean.TRUE);
		}
		if (element.getIndexVersion() == null) {
			element.setIndexVersion(1);
		}
		if (element.getNormalized() == null) {
			element.setNormalized(Boolean.FALSE);
		}
		if (element.getSyncedAt() == null) {
			// Stamped rather than left to the column default because jOOQ maps the whole POJO onto the
			// record, so a null here would be sent into a NOT NULL column instead of being omitted.
			// dirty is what actually drives the drain; synced_at only orders it, oldest first.
			element.setSyncedAt(LocalDateTime.now());
		}
	}

}
