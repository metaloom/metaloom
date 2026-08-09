package io.metaloom.loom.db.jooq.dao.detection;

import static io.metaloom.loom.db.jooq.tables.JooqAsset.ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqDetection.DETECTION;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.impl.DSL;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.enums.JooqReviewStatus;
import io.metaloom.loom.db.jooq.tables.JooqDetection;
import io.metaloom.loom.db.model.asset.AssetComponent;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.page.Page;

@Singleton
public class DetectionDaoImpl extends AbstractJooqDao<Detection> implements DetectionDao {

	/**
	 * Columns a producer's upsert must not write directly.
	 *
	 * <p>
	 * The verdict belongs to the reviewer, not to the node that proposed the box. Keeping these out of the generated UPDATE set leaves
	 * {@link #reviewOverrides()} as the single thing that decides what happens to them on a re-run.
	 * </p>
	 */
	private static final Set<String> REVIEW_COLUMNS = Set.of("status", "reviewed_at", "reviewer_uuid", "corrected_label");

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
		//
		// The review columns are not simply preserved, they are preserved conditionally - see reviewOverrides().
		upsert(detection, REVIEW_COLUMNS, reviewOverrides(),
			DETECTION.ASSET_UUID, DETECTION.NODE_KIND, DETECTION.FRAME_NUMBER, DETECTION.DETECTION_INDEX);
		return detection;
	}

	/**
	 * Hold the review verdict across a re-run of the producing node, unless the producer version changed.
	 *
	 * <p>
	 * Each column is set to a {@code CASE} comparing the stored {@code producer_version} against the incoming one:
	 * </p>
	 * <ul>
	 * <li>Same version - keep the stored value. The node is re-running the same model, {@code detection_index} still identifies the same object, and
	 * the human's answer about it still holds.</li>
	 * <li>Different version - reset to PENDING and clear the reviewer, timestamp and correction. {@code detection_index} is an ordinal, not an
	 * identity, and it is not stable across models: carrying a verdict forward would attribute a human decision to a box they never saw.</li>
	 * </ul>
	 *
	 * <p>
	 * Naming the columns in {@code preserved} as well keeps them out of the generated UPDATE set, so the only thing that writes them here is this map.
	 * </p>
	 */
	private static Map<Field<?>, Object> reviewOverrides() {
		Condition sameVersion = DETECTION.PRODUCER_VERSION.eq(DSL.excluded(DETECTION.PRODUCER_VERSION));
		Map<Field<?>, Object> overrides = new LinkedHashMap<>();
		overrides.put(DETECTION.STATUS, DSL.when(sameVersion, DETECTION.STATUS).otherwise(DSL.val(JooqReviewStatus.PENDING)));
		overrides.put(DETECTION.REVIEWED_AT, DSL.when(sameVersion, DETECTION.REVIEWED_AT).otherwise(DSL.val((LocalDateTime) null)));
		overrides.put(DETECTION.REVIEWER_UUID, DSL.when(sameVersion, DETECTION.REVIEWER_UUID).otherwise(DSL.val((UUID) null)));
		overrides.put(DETECTION.CORRECTED_LABEL, DSL.when(sameVersion, DETECTION.CORRECTED_LABEL).otherwise(DSL.val((String) null)));
		return overrides;
	}

	@Override
	public Page<Detection> loadPage(String status, String type, UUID fromId, int pageSize) {
		// Bespoke rather than the inherited loadPage: AbstractJooqDao.getField casts every sort column to Field<UUID>, so it cannot order by created.
		Condition condition = DSL.noCondition();
		if (status != null) {
			condition = condition.and(DETECTION.STATUS.eq(status(status)));
		}
		if (type != null) {
			condition = condition.and(DETECTION.TYPE.eq(type));
		}
		long totalCount = ctx().fetchCount(DETECTION, condition);

		if (fromId != null) {
			// Keyset seek in (created DESC, uuid DESC) order. A cursor row that has since been deleted yields no rows rather than silently
			// restarting from the top.
			LocalDateTime fromCreated = ctx().select(DETECTION.CREATED)
				.from(DETECTION)
				.where(DETECTION.UUID.eq(fromId))
				.fetchOne(DETECTION.CREATED);
			if (fromCreated == null) {
				return new Page<>(pageSize, totalCount, List.<Detection> of());
			}
			condition = condition.and(DSL.row(DETECTION.CREATED, DETECTION.UUID).lt(DSL.row(fromCreated, fromId)));
		}

		List<Detection> list = ctx().selectFrom(DETECTION)
			.where(condition)
			.orderBy(DETECTION.CREATED.desc(), DETECTION.UUID.desc())
			.limit(pageSize)
			.fetchInto(DetectionImpl.class)
			.stream()
			.map(d -> (Detection) d)
			.toList();

		return new Page<>(pageSize, totalCount, list);
	}

	@Override
	public List<Detection> listByStatus(UUID assetUuid, String type, String status) {
		Condition condition = DETECTION.ASSET_UUID.eq(assetUuid);
		if (type != null) {
			condition = condition.and(DETECTION.TYPE.eq(type));
		}
		if (status != null) {
			condition = condition.and(DETECTION.STATUS.eq(status(status)));
		}
		// Frame then ordinal: the order the reviewer walks the asset in, and the order the producer assigned.
		return ctx().selectFrom(DETECTION)
			.where(condition)
			.orderBy(DETECTION.FRAME_NUMBER.asc(), DETECTION.DETECTION_INDEX.asc())
			.fetchInto(DetectionImpl.class)
			.stream()
			.map(d -> (Detection) d)
			.toList();
	}

	@Override
	public Detection updateReview(UUID detectionUuid, String status, String correctedLabel, UUID reviewerUuid) {
		var step = ctx().update(DETECTION)
			.set(DETECTION.STATUS, status(status))
			.set(DETECTION.REVIEWED_AT, LocalDateTime.now(ZoneOffset.UTC))
			.set(DETECTION.REVIEWER_UUID, reviewerUuid);
		if (correctedLabel != null) {
			step = step.set(DETECTION.CORRECTED_LABEL, correctedLabel);
		}
		// edited/editor_uuid are deliberately untouched: they are producer provenance, and a review is not a production event. Same split
		// V2.81 introduced the separate reviewed_at/reviewer_uuid columns for.
		step.where(DETECTION.UUID.eq(detectionUuid)).execute();
		return load(detectionUuid);
	}

	private static JooqReviewStatus status(String status) {
		return status == null ? JooqReviewStatus.PENDING : JooqReviewStatus.lookupLiteral(status);
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
