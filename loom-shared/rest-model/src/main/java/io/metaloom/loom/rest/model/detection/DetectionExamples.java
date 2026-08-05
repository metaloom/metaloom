package io.metaloom.loom.rest.model.detection;

import java.util.Arrays;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonObject;

public interface DetectionExamples extends ExampleValues {

	default Example detectionCreateRequestExample() {
		return new ExampleImpl(detectionCreateRequest(), "The detection create request", HttpResponseStatus.CREATED);
	}

	default Example detectionUpdateRequestExample() {
		return new ExampleImpl(detectionUpdateRequest(), "The detection update request", HttpResponseStatus.OK);
	}

	default Example detectionResponseExample() {
		return new ExampleImpl(detectionResponse(), "The detection response", HttpResponseStatus.OK);
	}

	default Example detectionListResponseExample() {
		return new ExampleImpl(detectionListResponse(), "The detection list response", HttpResponseStatus.OK);
	}

	default Example detectionBulkCreateRequestExample() {
		return new ExampleImpl(detectionBulkCreateRequest(), "The detection bulk create request", HttpResponseStatus.CREATED);
	}

	default Example detectionBulkResponseExample() {
		return new ExampleImpl(detectionBulkResponse(), "The detection bulk response", HttpResponseStatus.OK);
	}

	default DetectionResponse detectionResponse() {
		DetectionResponse model = new DetectionResponse();
		model.setUuid(uuidC());
		model.setType("facedetection");
		model.setFrameNumber(0);
		model.setBboxX(0.3f);
		model.setBboxY(0.2f);
		model.setBboxWidth(0.12f);
		model.setBboxHeight(0.2f);
		model.setConfidence(0.97f);
		model.setAssetUuid(uuidA().toString());
		model.setMeta(new JsonObject().put("gender", "male").put("age", 30));
		setCreatorEditor(model);
		return model;
	}

	/**
	 * The other shape a detection comes in: a classed box from {@code objectdetect}.
	 *
	 * <p>
	 * Documented alongside the face example because {@code label} is populated for one and null for
	 * the other — a reader shown only the face response would reasonably conclude detections have no
	 * classes at all.
	 * </p>
	 */
	default DetectionResponse objectDetectionResponse() {
		DetectionResponse model = new DetectionResponse();
		model.setUuid(uuidB());
		model.setType("objectdetection");
		model.setLabel("dog");
		model.setFrameNumber(120);
		model.setBboxX(0.51f);
		model.setBboxY(0.34f);
		model.setBboxWidth(0.18f);
		model.setBboxHeight(0.27f);
		model.setConfidence(0.88f);
		model.setAssetUuid(uuidA().toString());
		setCreatorEditor(model);
		return model;
	}

	default DetectionCreateRequest detectionCreateRequest() {
		DetectionCreateRequest model = new DetectionCreateRequest();
		model.setType("facedetection");
		model.setFrameNumber(0);
		model.setBboxX(0.3f);
		model.setBboxY(0.2f);
		model.setBboxWidth(0.12f);
		model.setBboxHeight(0.2f);
		model.setConfidence(0.97f);
		model.setMeta(new JsonObject().put("gender", "male").put("age", 30));
		return model;
	}

	default DetectionUpdateRequest detectionUpdateRequest() {
		DetectionUpdateRequest model = new DetectionUpdateRequest();
		model.setType("facedetection");
		model.setFrameNumber(1);
		model.setBboxX(0.35f);
		model.setBboxY(0.25f);
		model.setBboxWidth(0.14f);
		model.setBboxHeight(0.22f);
		model.setConfidence(0.95f);
		model.setMeta(new JsonObject().put("gender", "female").put("age", 25));
		return model;
	}

	default DetectionListResponse detectionListResponse() {
		DetectionListResponse model = new DetectionListResponse();
		model.setMetainfo(pagingInfo());
		model.add(detectionResponse());
		model.add(objectDetectionResponse());
		return model;
	}

	default DetectionBulkCreateRequest detectionBulkCreateRequest() {
		DetectionBulkCreateRequest model = new DetectionBulkCreateRequest();
		model.setDetections(Arrays.asList(detectionCreateRequest(), detectionCreateRequest()));
		return model;
	}

	default DetectionBulkResponse detectionBulkResponse() {
		DetectionBulkResponse model = new DetectionBulkResponse();
		model.setTotal(2);
		model.setCreated(2);
		model.setFailed(0);
		model.add(detectionResponse());
		model.add(detectionResponse());
		return model;
	}

}
