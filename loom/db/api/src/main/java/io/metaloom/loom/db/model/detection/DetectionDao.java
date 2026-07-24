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

}
