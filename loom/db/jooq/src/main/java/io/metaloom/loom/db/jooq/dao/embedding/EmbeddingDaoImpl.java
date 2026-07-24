package io.metaloom.loom.db.jooq.dao.embedding;

import static io.metaloom.loom.db.jooq.tables.JooqEmbedding.EMBEDDING;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.api.embedding.EmbeddingType;
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
	public Embedding createEmbedding(UUID userUuid, UUID assetUuid, Float[] vector, EmbeddingType type) {
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
		backfillDimensions(element);
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
		backfillDimensions(element);
		// Idempotent on the (asset_uuid, node_kind, type, frame_number, subject_index) unique key.
		upsert(element, EMBEDDING.ASSET_UUID, EMBEDDING.NODE_KIND, EMBEDDING.TYPE, EMBEDDING.FRAME_NUMBER, EMBEDDING.SUBJECT_INDEX);
		return element;
	}

	private void backfillDimensions(Embedding element) {
		if (element.getDimensions() == null && element.getVector() != null) {
			element.setDimensions(element.getVector().length);
		}
	}

}
