package io.metaloom.loom.client.common.method;

import static io.metaloom.loom.api.asset.AssetId.assetId;

import java.util.UUID;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkReviewRequest;
import io.metaloom.loom.rest.model.detection.DetectionConfirmRequest;
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

	/**
	 * Download the cropped face image for a detection.
	 *
	 * <p>
	 * Served from the deployment's own storage: face crops are biometric data. The bytes are written by the face-detection node, so a detection whose
	 * node has not run answers 404.
	 * </p>
	 *
	 * @return the request, yielding a streaming response the caller must close
	 */
	LoomClientRequest<LoomBinaryResponse> loadDetectionCrop(AssetId assetId, UUID detectionUuid);

	default LoomClientRequest<LoomBinaryResponse> loadDetectionCrop(UUID assetUuid, UUID detectionUuid) {
		return loadDetectionCrop(assetId(assetUuid), detectionUuid);
	}

	LoomClientRequest<DetectionBulkResponse> bulkCreateAssetDetections(AssetId assetId, DetectionBulkCreateRequest request);

	default LoomClientRequest<DetectionBulkResponse> bulkCreateAssetDetections(UUID assetUuid, DetectionBulkCreateRequest request) {
		return bulkCreateAssetDetections(assetId(assetUuid), request);
	}

	/**
	 * Confirm a detection: the producer found something real here.
	 *
	 * @param request optional; supply a {@code correctedLabel} when the box was right but the class was wrong. May be null.
	 */
	LoomClientRequest<DetectionResponse> confirmAssetDetection(AssetId assetId, UUID detectionUuid, DetectionConfirmRequest request);

	default LoomClientRequest<DetectionResponse> confirmAssetDetection(UUID assetUuid, UUID detectionUuid, DetectionConfirmRequest request) {
		return confirmAssetDetection(assetId(assetUuid), detectionUuid, request);
	}

	/** Reject a detection as a false positive. The row is kept as the record that the producer was wrong here. */
	LoomClientRequest<DetectionResponse> rejectAssetDetection(AssetId assetId, UUID detectionUuid);

	default LoomClientRequest<DetectionResponse> rejectAssetDetection(UUID assetUuid, UUID detectionUuid) {
		return rejectAssetDetection(assetId(assetUuid), detectionUuid);
	}

	/** Record many verdicts on one asset in a single request - the shape keyboard review needs. */
	LoomClientRequest<DetectionBulkResponse> bulkReviewAssetDetections(AssetId assetId, DetectionBulkReviewRequest request);

	default LoomClientRequest<DetectionBulkResponse> bulkReviewAssetDetections(UUID assetUuid, DetectionBulkReviewRequest request) {
		return bulkReviewAssetDetections(assetId(assetUuid), request);
	}

	/**
	 * The cross-asset review queue.
	 *
	 * @param status one of PENDING, CONFIRMED, REJECTED, or null for any
	 * @param type   the detection type, or null for any
	 */
	LoomClientRequest<DetectionListResponse> listDetections(String status, String type);

	default LoomClientRequest<DetectionListResponse> listDetections() {
		return listDetections(null, null);
	}

}
