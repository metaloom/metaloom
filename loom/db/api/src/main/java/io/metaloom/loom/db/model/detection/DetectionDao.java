package io.metaloom.loom.db.model.detection;

import java.util.List;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface DetectionDao extends CRUDDao<Detection> {

	default Detection createDetection(User user, String type) {
		return createDetection(user.getUuid(), type);
	}

	Detection createDetection(UUID userUuid, String type);

	/**
	 * Insert the detection, or replace the conflicting row keyed by {@code (asset_uuid, node_kind, frame_number, detection_index)}. A face-detection
	 * node that runs again rewrites its own rows instead of appending duplicates.
	 *
	 * @param detection the detection to persist; its uuid is populated on return
	 * @return the persisted detection
	 */
	Detection upsertDetection(Detection detection);

	Detection loadAssetDetection(AssetId assetId, UUID detectionUuid);

	Page<Detection> loadPageForAsset(AssetId assetId, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	/**
	 * Load a keyset-paged slice of detections across every asset, optionally narrowed to a review status and a detection type.
	 *
	 * <p>
	 * This is the cross-asset review queue behind {@code GET /api/v1/detections?status=PENDING&type=objectdetection}. Both filters are optional; a null
	 * means "any".
	 * </p>
	 *
	 * @param status  one of the {@link io.metaloom.loom.db.model.review.ReviewStatus} constants, or null for any
	 * @param type    the detection type, or null for any
	 * @param fromId  keyset cursor, or null to start at the newest
	 * @param pageSize maximum rows to return
	 * @return the page
	 */
	Page<Detection> loadPage(String status, String type, UUID fromId, int pageSize);

	/**
	 * List the detections of one asset, optionally narrowed to a type and a review status.
	 *
	 * <p>
	 * The per-asset counterpart of {@link #loadPage(String, String, UUID, int)}: what is left to review on the asset currently open in the review UI.
	 * </p>
	 *
	 * @param assetUuid the asset
	 * @param type      the detection type, or null for any
	 * @param status    one of the {@link io.metaloom.loom.db.model.review.ReviewStatus} constants, or null for any
	 * @return the matching detections, ordered by frame then ordinal
	 */
	List<Detection> listByStatus(UUID assetUuid, String type, String status);

	/**
	 * Record a human verdict against a detection.
	 *
	 * <p>
	 * Writes {@code status}, {@code reviewed_at} and {@code reviewer_uuid}, and {@code corrected_label} when one is supplied. It deliberately does
	 * <em>not</em> touch {@code edited}/{@code editor_uuid}: those are producer provenance, and a review is not a production event.
	 * </p>
	 *
	 * @param detectionUuid  the detection to decide on
	 * @param status         one of the {@link io.metaloom.loom.db.model.review.ReviewStatus} constants
	 * @param correctedLabel a reviewer-supplied class, or null to leave any existing correction alone
	 * @param reviewerUuid   the deciding user
	 * @return the updated detection
	 */
	Detection updateReview(UUID detectionUuid, String status, String correctedLabel, UUID reviewerUuid);

}
