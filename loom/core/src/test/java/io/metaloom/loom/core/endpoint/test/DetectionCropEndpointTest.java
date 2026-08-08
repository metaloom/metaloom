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

			// A binary download request does not throw on a non-2xx the way a JSON one does, so the status is
			// read off the response rather than asserted with expect(...).
			try (var response = client.loadDetectionCrop(ASSET_UUID, detection.getUuid()).sync().body()) {
				assertEquals(404, response.code(), "a detection with no stored crop has nothing to serve");
			}
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

			try (var response = client.loadDetectionCrop(UUID.randomUUID(), detection.getUuid()).sync().body()) {
				assertEquals(404, response.code(), "the (asset, detection) pair is the address; a mismatch is missing, not forbidden");
			}
		}
	}

	// ---------------------------------------------------------------------------------------------

	private DetectionResponse createFaceDetection(LoomHttpClient client) throws LoomClientException {
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
		return client.bulkCreateAssetDetections(ASSET_UUID, request).sync().body().getDetections().get(0);
	}

	private AttachmentResponse uploadCrop(LoomHttpClient client, UUID detectionUuid) throws Exception {
		File file = File.createTempFile("face-crop-", ".jpg");
		try {
			ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpg", file);
			return client.uploadFaceCrop(file, ASSET_UUID, detectionUuid, "192", "facedetect").sync().body();
		} finally {
			file.delete();
		}
	}

}
