package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.attachment.AttachmentResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionResponse;

/**
 * Covers {@code GET /assets/:uuid/detections/:detectionUuid/crop}.
 *
 * <p>
 * The route exists so face crops are served from this deployment rather than stood in with portraits from a third-party avatar service - the review UI
 * did the latter, which both leaked detection uuids and showed the reviewer a stranger. Face data is biometric; it does not leave the deployment.
 * </p>
 */
public class DetectionCropEndpointTest extends AbstractEndpointTest {

	/** A stored crop is streamed back as an image, and is cacheable. */
	@Test
	public void testServesAStoredCrop() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			DetectionResponse detection = createFaceDetection(client);
			uploadCrop(client, detection.getUuid());

			try (var response = client.loadDetectionCrop(ASSET_UUID, detection.getUuid()).sync().body()) {
				assertNotNull(response, "the crop must be streamed back");
				byte[] bytes = response.getStream().readAllBytes();
				assertTrue(bytes.length > 0, "the crop must have bytes");
				assertEquals("image/jpeg", response.getContentType());
			}
		}
	}

	/**
	 * A detection with no stored crop answers 404 rather than inventing one: the server has no imaging libraries and cannot decode a video frame, so
	 * "no crop yet" is a real and permanent state until the node runs.
	 */
	@Test
	public void testAnswers404WhenNoCropHasBeenStored() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			DetectionResponse detection = createFaceDetection(client);

			// A detection with no stored crop has nothing to serve.
			expect(404, "Not Found", client.loadDetectionCrop(ASSET_UUID, detection.getUuid()));
		}
	}

	/**
	 * The (asset, detection) pair is the address. A detection belonging to another asset is answered as missing rather than as forbidden - confirming
	 * that the uuid exists elsewhere would leak it.
	 */
	@Test
	public void testAnswers404ForADetectionOfAnotherAsset() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			DetectionResponse detection = createFaceDetection(client);

			// The (asset, detection) pair is the address; a mismatch is missing, not forbidden.
			expect(404, "Not Found", client.loadDetectionCrop(UUID.randomUUID(), detection.getUuid()));
		}
	}

	/**
	 * Deleting the asset takes its detection with it (V2.43 {@code detection.asset_uuid ON DELETE CASCADE}), and the crop attachment cascades from the
	 * detection in turn (V2.79 {@code attachment.detection_uuid ON DELETE CASCADE}). The route must answer 404, not a dangling reference.
	 */
	@Test
	public void testCropIsGoneAfterItsAssetIsDeleted() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			io.metaloom.loom.db.model.asset.Asset asset = seedAsset("crop-cascade.jpg");
			DetectionResponse detection = createFaceDetection(client, asset.getUuid());
			uploadCrop(client, asset.getUuid(), detection.getUuid());

			try (var response = client.loadDetectionCrop(asset.getUuid(), detection.getUuid()).sync().body()) {
				assertTrue(response.getStream().readAllBytes().length > 0, "the crop exists before the delete");
			}

			daos().assetDao().delete(asset.getUuid());

			expect(404, "Not Found", client.loadDetectionCrop(asset.getUuid(), detection.getUuid()));
		}
	}

	private io.metaloom.loom.db.model.asset.Asset seedAsset(String filename) {
		io.metaloom.loom.db.dagger.DaoCollection daos = daos();
		io.metaloom.loom.db.model.asset.Asset asset = daos.assetDao().createAsset(adminUuid(),
			io.metaloom.utils.hash.SHA512.fromString(UUID.randomUUID().toString().replace("-", "").repeat(4)),
			"image/jpeg", filename, "/media/" + filename, 42L);
		daos.assetDao().store(asset);
		return asset;
	}

	// ---------------------------------------------------------------------------------------------

	private DetectionResponse createFaceDetection(LoomHttpClient client) throws LoomClientException {
		return createFaceDetection(client, ASSET_UUID);
	}

	private DetectionResponse createFaceDetection(LoomHttpClient client, UUID assetUuid) throws LoomClientException {
		DetectionBulkCreateRequest request = new DetectionBulkCreateRequest();
		request.getDetections().add(new DetectionCreateRequest()
			.setType("face")
			.setNodeKind("facedetect")
			.setDetectionIndex(0)
			.setFrameNumber(0)
			.setBboxX(0.25f)
			.setBboxY(0.15f)
			.setBboxWidth(0.1f)
			.setBboxHeight(0.2f)
			.setConfidence(0.95f));
		return client.bulkCreateAssetDetections(assetUuid, request).sync().body().getDetections().get(0);
	}

	private AttachmentResponse uploadCrop(LoomHttpClient client, UUID detectionUuid) throws Exception {
		return uploadCrop(client, ASSET_UUID, detectionUuid);
	}

	private AttachmentResponse uploadCrop(LoomHttpClient client, UUID assetUuid, UUID detectionUuid) throws Exception {
		File file = File.createTempFile("face-crop-", ".jpg");
		try {
			ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpg", file);
			return client.uploadFaceCrop(file, assetUuid, detectionUuid, "192", "facedetect").sync().body();
		} finally {
			file.delete();
		}
	}

}
