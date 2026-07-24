package io.metaloom.loom.db.jooq.dao.asset;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import io.metaloom.loom.db.model.asset.AssetNodeResult;
import io.metaloom.loom.db.model.asset.AssetNodeResultDao;
import io.vertx.core.json.JsonObject;

/**
 * jOOQ implementation of the per-asset processing ledger.
 */
@Singleton
public class AssetNodeResultDaoImpl implements AssetNodeResultDao {

	private static final Table<?> TABLE = DSL.table("asset_node_result");

	private static final Field<UUID> F_UUID = DSL.field("uuid", UUID.class);
	private static final Field<UUID> F_ASSET_UUID = DSL.field("asset_uuid", UUID.class);
	private static final Field<String> F_NODE_KIND = DSL.field("node_kind", String.class);
	private static final Field<String> F_NODE_ID = DSL.field("node_id", String.class);
	private static final Field<String> F_PRODUCER_VERSION = DSL.field("producer_version", String.class);
	private static final Field<String> F_STATE = DSL.field("state", String.class);
	private static final Field<String> F_ORIGIN = DSL.field("origin", String.class);
	private static final Field<String> F_REASON = DSL.field("reason", String.class);
	private static final Field<UUID> F_RUN_UUID = DSL.field("run_uuid", UUID.class);
	private static final Field<UUID> F_TASK_UUID = DSL.field("task_uuid", UUID.class);
	private static final Field<Instant> F_STARTED = DSL.field("started", Instant.class);
	private static final Field<Instant> F_FINISHED = DSL.field("finished", Instant.class);
	private static final Field<Long> F_DURATION_MS = DSL.field("duration_ms", Long.class);
	private static final Field<JSONB> F_RESULT_REF = DSL.field("result_ref", SQLDataType.JSONB);
	private static final Field<JSONB> F_META = DSL.field("meta", SQLDataType.JSONB);
	private static final Field<Instant> F_CREATED = DSL.field("created", Instant.class);
	private static final Field<UUID> F_CREATOR_UUID = DSL.field("creator_uuid", UUID.class);
	private static final Field<Instant> F_EDITED = DSL.field("edited", Instant.class);
	private static final Field<UUID> F_EDITOR_UUID = DSL.field("editor_uuid", UUID.class);

	private final DSLContext ctx;

	@Inject
	public AssetNodeResultDaoImpl(DSLContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public String getTypeName() {
		return "AssetNodeResult";
	}

	@Override
	public void clear() {
		ctx.deleteFrom(TABLE).execute();
	}

	@Override
	public long count() {
		return ctx.fetchCount(TABLE);
	}

	@Override
	public AssetNodeResult createNodeResult(UUID userUuid, UUID assetUuid, String nodeKind, String nodeId) {
		AssetNodeResultImpl result = new AssetNodeResultImpl();
		result.setAssetUuid(assetUuid);
		result.setNodeKind(nodeKind);
		result.setNodeId(nodeId);
		result.setCreatorUuid(userUuid);
		result.setEditorUuid(userUuid);
		result.setCreated(Instant.now());
		result.setEdited(Instant.now());
		return result;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public AssetNodeResult upsert(AssetNodeResult result) {
		Map<Field<?>, Object> values = values(result);
		Map<Field<?>, Object> updates = new LinkedHashMap<>(values);
		updates.remove(F_ASSET_UUID);
		updates.remove(F_NODE_KIND);
		updates.remove(F_NODE_ID);
		// The ledger row keeps the timestamp of the first run; everything else is rewritten.
		updates.remove(F_CREATED);
		updates.remove(F_CREATOR_UUID);

		UUID uuid = ctx.insertInto(TABLE)
			.set((Map) values)
			.onConflict(F_ASSET_UUID, F_NODE_KIND, F_NODE_ID)
			.doUpdate()
			.set((Map) updates)
			.returning(F_UUID)
			.fetchOne(F_UUID);
		result.setUuid(uuid);
		return result;
	}

	@Override
	public AssetNodeResult load(UUID uuid) {
		return ctx.select(DSL.asterisk())
			.from(TABLE)
			.where(F_UUID.eq(uuid))
			.fetchOne(this::map);
	}

	@Override
	public AssetNodeResult loadByNode(UUID assetUuid, String nodeKind, String nodeId) {
		return ctx.select(DSL.asterisk())
			.from(TABLE)
			.where(F_ASSET_UUID.eq(assetUuid)
				.and(F_NODE_KIND.eq(nodeKind))
				.and(F_NODE_ID.eq(nodeId == null ? "" : nodeId)))
			.fetchOne(this::map);
	}

	@Override
	public List<AssetNodeResult> loadByAsset(UUID assetUuid) {
		return ctx.select(DSL.asterisk())
			.from(TABLE)
			.where(F_ASSET_UUID.eq(assetUuid))
			.fetch(this::map);
	}

	@Override
	public List<AssetNodeResult> findStale(String nodeKind, String currentVersion) {
		return ctx.select(DSL.asterisk())
			.from(TABLE)
			.where(F_NODE_KIND.eq(nodeKind)
				.and(F_PRODUCER_VERSION.ne(currentVersion == null ? "" : currentVersion)))
			.fetch(this::map);
	}

	@Override
	public void delete(UUID uuid) {
		ctx.deleteFrom(TABLE).where(F_UUID.eq(uuid)).execute();
	}

	private Map<Field<?>, Object> values(AssetNodeResult result) {
		Map<Field<?>, Object> values = new LinkedHashMap<>();
		values.put(F_ASSET_UUID, result.getAssetUuid());
		values.put(F_NODE_KIND, result.getNodeKind());
		values.put(F_NODE_ID, result.getNodeId() == null ? "" : result.getNodeId());
		values.put(F_PRODUCER_VERSION, result.getProducerVersion() == null ? "" : result.getProducerVersion());
		values.put(F_STATE, result.getState());
		values.put(F_ORIGIN, result.getOrigin());
		values.put(F_REASON, result.getReason());
		values.put(F_RUN_UUID, result.getRunUuid());
		values.put(F_TASK_UUID, result.getTaskUuid());
		values.put(F_STARTED, result.getStarted());
		values.put(F_FINISHED, result.getFinished());
		values.put(F_DURATION_MS, result.getDurationMs());
		values.put(F_RESULT_REF, toJsonb(result.getResultRef()));
		values.put(F_META, toJsonb(result.getMeta()));
		values.put(F_CREATED, result.getCreated());
		values.put(F_CREATOR_UUID, result.getCreatorUuid());
		values.put(F_EDITED, result.getEdited());
		values.put(F_EDITOR_UUID, result.getEditorUuid());
		return values;
	}

	private AssetNodeResult map(Record r) {
		AssetNodeResultImpl result = new AssetNodeResultImpl();
		result.setUuid(r.get(F_UUID));
		result.setAssetUuid(r.get(F_ASSET_UUID));
		result.setNodeKind(r.get(F_NODE_KIND));
		result.setNodeId(r.get(F_NODE_ID));
		result.setProducerVersion(r.get(F_PRODUCER_VERSION));
		result.setState(r.get(F_STATE));
		result.setOrigin(r.get(F_ORIGIN));
		result.setReason(r.get(F_REASON));
		result.setRunUuid(r.get(F_RUN_UUID));
		result.setTaskUuid(r.get(F_TASK_UUID));
		result.setStarted(toInstant(r.get("started")));
		result.setFinished(toInstant(r.get("finished")));
		result.setDurationMs(r.get(F_DURATION_MS));
		result.setResultRef(toJsonObject(r.get(F_RESULT_REF)));
		result.setMeta(toJsonObject(r.get(F_META)));
		result.setCreated(toInstant(r.get("created")));
		result.setCreatorUuid(r.get(F_CREATOR_UUID));
		result.setEdited(toInstant(r.get("edited")));
		result.setEditorUuid(r.get(F_EDITOR_UUID));
		return result;
	}

	private static JSONB toJsonb(JsonObject json) {
		return json == null ? null : JSONB.jsonb(json.encode());
	}

	private static JsonObject toJsonObject(JSONB jsonb) {
		return jsonb == null ? null : new JsonObject(jsonb.data());
	}

	private static Instant toInstant(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Instant i) {
			return i;
		}
		if (value instanceof java.sql.Timestamp ts) {
			return ts.toInstant();
		}
		if (value instanceof java.time.LocalDateTime ldt) {
			return ldt.atZone(java.time.ZoneOffset.UTC).toInstant();
		}
		throw new IllegalArgumentException("Cannot convert " + value.getClass() + " to Instant");
	}
}
