package io.metaloom.loom.db.jooq.dao.detection;

import static io.metaloom.loom.db.jooq.tables.JooqAsset.ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqDetection.DETECTION;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqDetection;
import io.metaloom.loom.db.model.asset.AssetComponent;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.page.Page;

@Singleton
public class DetectionDaoImpl extends AbstractJooqDao<Detection> implements DetectionDao {

	@Inject
	public DetectionDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Detections";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqDetection.DETECTION;
	}

	@Override
	protected Class<? extends Detection> getPojoClass() {
		return DetectionImpl.class;
	}

	@Override
	public Detection createDetection(UUID userUuid, String type) {
		Detection detection = new DetectionImpl();
		detection.setType(type);
		// Detections created through the API rather than by a node are attributed to the user.
		// A node overrides this with its own kind before storing.
		detection.setNodeKind(AssetComponent.NODE_KIND_MANUAL);
		setCreatorEditor(detection, userUuid);
		return detection;
	}

	@Override
	public Detection upsertDetection(Detection detection) {
		// Idempotent on the (asset_uuid, node_kind, frame_number, detection_index) unique key.
		upsert(detection, DETECTION.ASSET_UUID, DETECTION.NODE_KIND, DETECTION.FRAME_NUMBER, DETECTION.DETECTION_INDEX);
		return detection;
	}

	@Override
	public Page<Detection> loadPageForAsset(AssetId assetId, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		SelectConditionStep<Record> query = null;
		if (assetId.isUUID()) {
			query = ctx()
				.select()
				.from(DETECTION)
				.where(DETECTION.ASSET_UUID.eq(assetId.uuid()));
		} else {
			query = ctx()
				.select()
				.from(DETECTION)
				.join(ASSET)
				.on(ASSET.UUID.eq(DETECTION.ASSET_UUID))
				.where(ASSET.SHA512SUM.eq(assetId.hashsum().toString()));
		}

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public Detection loadAssetDetection(AssetId assetId, UUID detectionUuid) {
		if (assetId.isUUID()) {
			return ctx()
				.select(DETECTION)
				.from(DETECTION)
				.where(DETECTION.UUID.eq(detectionUuid))
				.and(DETECTION.ASSET_UUID.eq(assetId.uuid()))
				.fetchOneInto(getPojoClass());
		} else {
			return ctx()
				.select(DETECTION)
				.from(DETECTION)
				.join(ASSET)
				.on(ASSET.UUID.eq(DETECTION.ASSET_UUID))
				.where(DETECTION.UUID.eq(detectionUuid))
				.and(ASSET.SHA512SUM.eq(assetId.hashsum().toString()))
				.fetchOneInto(getPojoClass());
		}
	}

}
