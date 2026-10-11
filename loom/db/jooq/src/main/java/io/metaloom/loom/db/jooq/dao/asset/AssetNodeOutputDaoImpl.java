package io.metaloom.loom.db.jooq.dao.asset;

import static io.metaloom.loom.db.jooq.tables.JooqAssetNodeOutput.ASSET_NODE_OUTPUT;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;

import io.metaloom.loom.db.model.asset.AssetNodeOutput;
import io.metaloom.loom.db.model.asset.AssetNodeOutputDao;
import io.metaloom.loom.db.model.asset.AssetNodeResult;
import io.vertx.core.json.JsonObject;

/**
 * jOOQ implementation of {@link AssetNodeOutputDao}.
 */
@Singleton
public class AssetNodeOutputDaoImpl implements AssetNodeOutputDao {

	private final DSLContext ctx;

	@Inject
	public AssetNodeOutputDaoImpl(DSLContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public String getTypeName() {
		return "AssetNodeOutputs";
	}

	@Override
	public void clear() {
		ctx.deleteFrom(ASSET_NODE_OUTPUT).execute();
	}

	@Override
	public long count() {
		return ctx.fetchCount(ASSET_NODE_OUTPUT);
	}

	@Override
	public AssetNodeOutput upsert(UUID assetUuid, String nodeKind, String reuseKey, String nodeId, String producerVersion, String state,
		String reason, JsonObject outputs, UUID runUuid) {
		if (!AssetNodeResult.STATE_SUCCESS.equals(state) && !AssetNodeResult.STATE_SKIPPED.equals(state)) {
			// The table's CHECK says the same thing; failing here names the mistake instead of a constraint.
			throw new IllegalArgumentException("Only a SUCCESS or SKIPPED outcome can be stored for reuse, got " + state);
		}
		String id = nodeId == null ? "" : nodeId;
		String version = producerVersion == null ? "" : producerVersion;
		JsonObject payload = outputs == null ? new JsonObject() : outputs;
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		return ctx.insertInto(ASSET_NODE_OUTPUT)
			.set(ASSET_NODE_OUTPUT.ASSET_UUID, assetUuid)
			.set(ASSET_NODE_OUTPUT.NODE_KIND, nodeKind)
			.set(ASSET_NODE_OUTPUT.REUSE_KEY, reuseKey)
			.set(ASSET_NODE_OUTPUT.NODE_ID, id)
			.set(ASSET_NODE_OUTPUT.PRODUCER_VERSION, version)
			.set(ASSET_NODE_OUTPUT.STATE, state)
			.set(ASSET_NODE_OUTPUT.REASON, reason)
			.set(ASSET_NODE_OUTPUT.OUTPUTS, payload)
			.set(ASSET_NODE_OUTPUT.RUN_UUID, runUuid)
			.set(ASSET_NODE_OUTPUT.CREATED, now)
			.set(ASSET_NODE_OUTPUT.EDITED, now)
			// A recompute writes the same key again: the newer answer replaces the older one, and the row keeps its first creation time.
			.onConflict(ASSET_NODE_OUTPUT.ASSET_UUID, ASSET_NODE_OUTPUT.NODE_KIND, ASSET_NODE_OUTPUT.REUSE_KEY)
			.doUpdate()
			.set(ASSET_NODE_OUTPUT.NODE_ID, id)
			.set(ASSET_NODE_OUTPUT.PRODUCER_VERSION, version)
			.set(ASSET_NODE_OUTPUT.STATE, state)
			.set(ASSET_NODE_OUTPUT.REASON, reason)
			.set(ASSET_NODE_OUTPUT.OUTPUTS, payload)
			.set(ASSET_NODE_OUTPUT.RUN_UUID, runUuid)
			.set(ASSET_NODE_OUTPUT.EDITED, now)
			.returning()
			.fetchOne(this::map);
	}

	@Override
	public AssetNodeOutput loadByKey(UUID assetUuid, String nodeKind, String reuseKey) {
		if (assetUuid == null || nodeKind == null || reuseKey == null) {
			return null;
		}
		return ctx.selectFrom(ASSET_NODE_OUTPUT)
			.where(ASSET_NODE_OUTPUT.ASSET_UUID.eq(assetUuid)
				.and(ASSET_NODE_OUTPUT.NODE_KIND.eq(nodeKind))
				.and(ASSET_NODE_OUTPUT.REUSE_KEY.eq(reuseKey)))
			.fetchOne(this::map);
	}

	@Override
	public List<AssetNodeOutput> listByAsset(UUID assetUuid, String nodeKind) {
		return ctx.selectFrom(ASSET_NODE_OUTPUT)
			.where(byAsset(assetUuid, nodeKind))
			.orderBy(ASSET_NODE_OUTPUT.CREATED, ASSET_NODE_OUTPUT.UUID)
			.fetch(this::map);
	}

	@Override
	public int deleteByAsset(UUID assetUuid, String nodeKind) {
		return ctx.deleteFrom(ASSET_NODE_OUTPUT)
			.where(byAsset(assetUuid, nodeKind))
			.execute();
	}

	private static Condition byAsset(UUID assetUuid, String nodeKind) {
		Condition condition = ASSET_NODE_OUTPUT.ASSET_UUID.eq(assetUuid);
		return nodeKind == null ? condition : condition.and(ASSET_NODE_OUTPUT.NODE_KIND.eq(nodeKind));
	}

	private AssetNodeOutput map(Record r) {
		return new AssetNodeOutput(
			r.get(ASSET_NODE_OUTPUT.UUID),
			r.get(ASSET_NODE_OUTPUT.ASSET_UUID),
			r.get(ASSET_NODE_OUTPUT.NODE_KIND),
			r.get(ASSET_NODE_OUTPUT.REUSE_KEY),
			r.get(ASSET_NODE_OUTPUT.NODE_ID),
			r.get(ASSET_NODE_OUTPUT.PRODUCER_VERSION),
			r.get(ASSET_NODE_OUTPUT.STATE),
			r.get(ASSET_NODE_OUTPUT.REASON),
			r.get(ASSET_NODE_OUTPUT.OUTPUTS),
			r.get(ASSET_NODE_OUTPUT.RUN_UUID),
			toInstant(r.get(ASSET_NODE_OUTPUT.CREATED)),
			toInstant(r.get(ASSET_NODE_OUTPUT.EDITED)));
	}

	private static Instant toInstant(LocalDateTime ldt) {
		return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
	}
}
