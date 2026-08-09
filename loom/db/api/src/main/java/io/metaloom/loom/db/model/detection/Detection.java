package io.metaloom.loom.db.model.detection;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;
import io.metaloom.loom.db.model.review.ReviewStatus;

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

	/**
	 * Return the review verdict: one of the {@link ReviewStatus} constants, never null.
	 *
	 * <p>
	 * A {@code String} rather than an enum because {@code loom-db-api} cannot depend on the generated jOOQ types; the DAO converts at the database
	 * boundary. Backed by the shared {@code review_status} Postgres enum (V2.81).
	 * </p>
	 */
	String getStatus();

	Detection setStatus(String status);

	/**
	 * Return when a human recorded a verdict, or null while the detection is still {@link ReviewStatus#PENDING}.
	 *
	 * <p>
	 * Deliberately not {@code edited}: the producing node touches that on every re-run, so reading it as a review timestamp would report a review
	 * that never happened.
	 * </p>
	 */
	Instant getReviewedAt();

	Detection setReviewedAt(Instant reviewedAt);

	/**
	 * Return the user who recorded the verdict, or null while PENDING.
	 *
	 * <p>
	 * Separate from {@code editorUuid}, which is machine-written provenance and is NULL for a node-written row (V2.47).
	 * </p>
	 */
	UUID getReviewerUuid();

	Detection setReviewerUuid(UUID reviewerUuid);

	/**
	 * Return the label a reviewer supplied when the box was right but its class was wrong, or null when nobody corrected it.
	 *
	 * <p>
	 * Never written into {@link #getLabel()}: that holds what the model actually said, which is the training signal.
	 * </p>
	 */
	String getCorrectedLabel();

	Detection setCorrectedLabel(String correctedLabel);

}
