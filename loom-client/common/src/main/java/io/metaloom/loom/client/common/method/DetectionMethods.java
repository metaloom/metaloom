package io.metaloom.loom.client.common.method;

import static io.metaloom.loom.api.asset.AssetId.assetId;

import java.util.UUID;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionListResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.detection.DetectionUpdateRequest;

public interface DetectionMethods {

	LoomClientRequest<DetectionResponse> loadAssetDetection(AssetId assetId, UUID detectionUuid);

	default LoomClientRequest<DetectionResponse> loadAssetDetection(UUID assetUuid, UUID detectionUuid) {
		return loadAssetDetection(assetId(assetUuid), detectionUuid);
	}

	LoomClientRequest<DetectionResponse> createAssetDetection(AssetId assetId, DetectionCreateRequest request);

	default LoomClientRequest<DetectionResponse> createAssetDetection(UUID assetUuid, DetectionCreateRequest request) {
		return createAssetDetection(assetId(assetUuid), request);
	}

	LoomClientRequest<DetectionResponse> updateAssetDetection(AssetId assetId, UUID detectionUuid, DetectionUpdateRequest request);

	default LoomClientRequest<DetectionResponse> updateAssetDetection(UUID assetUuid, UUID detectionUuid, DetectionUpdateRequest request) {
		return updateAssetDetection(assetId(assetUuid), detectionUuid, request);
	}

	LoomClientRequest<DetectionListResponse> listAssetDetections(AssetId assetId);

	default LoomClientRequest<DetectionListResponse> listAssetDetections(UUID assetUuid) {
		return listAssetDetections(assetId(assetUuid));
	}

	LoomClientRequest<NoResponse> deleteAssetDetection(AssetId assetId, UUID detectionUuid);

	default LoomClientRequest<NoResponse> deleteAssetDetection(UUID assetUuid, UUID detectionUuid) {
		return deleteAssetDetection(assetId(assetUuid), detectionUuid);
	}

	LoomClientRequest<DetectionBulkResponse> bulkCreateAssetDetections(AssetId assetId, DetectionBulkCreateRequest request);

	default LoomClientRequest<DetectionBulkResponse> bulkCreateAssetDetections(UUID assetUuid, DetectionBulkCreateRequest request) {
		return bulkCreateAssetDetections(assetId(assetUuid), request);
	}

}
