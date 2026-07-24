package io.metaloom.loom.db.model.detection;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

public interface Detection extends CUDElement<Detection>, MetaElement<Detection> {

	/**
	 * Return the kind of node that produced this detection (e.g. "facedetect", "manual").
	 */
	String getNodeKind();

	Detection setNodeKind(String nodeKind);

	/**
	 * Return the model or algorithm version of the producer. Never null - an unknown version is the empty string.
	 */
	String getProducerVersion();

	Detection setProducerVersion(String producerVersion);

	/**
	 * Return the ordinal of this detection within its frame. Together with the frame number and the node kind it forms the identity, so a re-run
	 * replaces rather than duplicates.
	 */
	Integer getDetectionIndex();

	Detection setDetectionIndex(Integer detectionIndex);

	/**
	 * Return the detected class for object detection, e.g. dog.
	 */
	String getLabel();

	Detection setLabel(String label);

	/**
	 * Return the millisecond offset of the frame, for video.
	 */
	Long getTimeFrom();

	Detection setTimeFrom(Long timeFrom);

	String getType();

	Detection setType(String type);

	Integer getFrameNumber();

	Detection setFrameNumber(Integer frameNumber);

	Float getBboxX();

	Detection setBboxX(Float bboxX);

	Float getBboxY();

	Detection setBboxY(Float bboxY);

	Float getBboxWidth();

	Detection setBboxWidth(Float bboxWidth);

	Float getBboxHeight();

	Detection setBboxHeight(Float bboxHeight);

	Float getConfidence();

	Detection setConfidence(Float confidence);

	UUID getAssetUuid();

	Detection setAssetUuid(UUID assetUuid);

}
