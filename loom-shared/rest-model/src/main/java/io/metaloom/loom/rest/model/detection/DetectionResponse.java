package io.metaloom.loom.rest.model.detection;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class DetectionResponse extends AbstractCreatorEditorRestResponse<DetectionResponse>
	implements DetectionModel<DetectionResponse> {

	private String type;

	/**
	 * The detected class, for a detection that has one.
	 *
	 * <p>
	 * Null for a face — {@code facedetect} has no classes to report. It was missing here entirely
	 * until {@code objectdetect} arrived: the column, the create request and the DAO all carried it,
	 * so a label could be written and then never read back.
	 * </p>
	 */
	private String label;

	private Integer frameNumber;

	private Float bboxX;

	private Float bboxY;

	private Float bboxWidth;

	private Float bboxHeight;

	private Float confidence;

	private String assetUuid;

	private String reviewStatus;

	private String reviewedAt;

	private String correctedLabel;

	/**
	 * Review state: PENDING, CONFIRMED or REJECTED.
	 *
	 * <p>
	 * Named {@code reviewStatus} rather than {@code status} because {@link AbstractCreatorEditorRestResponse} already publishes a {@code status} object
	 * carrying the creator/editor audit block. Same choice, for the same reason, as {@code ClusterResponse}.
	 * </p>
	 */
	public String getReviewStatus() {
		return reviewStatus;
	}

	public DetectionResponse setReviewStatus(String reviewStatus) {
		this.reviewStatus = reviewStatus;
		return this;
	}

	/**
	 * When a human decided, or null while PENDING.
	 *
	 * <p>
	 * Distinct from the {@code edited} timestamp inside the audit block: the producing node touches that on every re-run, so it says nothing about
	 * whether anybody reviewed the row.
	 * </p>
	 */
	public String getReviewedAt() {
		return reviewedAt;
	}

	public DetectionResponse setReviewedAt(String reviewedAt) {
		this.reviewedAt = reviewedAt;
		return this;
	}

	/** The class a reviewer corrected this to, or null when nobody corrected it. {@link #getLabel()} keeps what the model said. */
	public String getCorrectedLabel() {
		return correctedLabel;
	}

	public DetectionResponse setCorrectedLabel(String correctedLabel) {
		this.correctedLabel = correctedLabel;
		return this;
	}

	@Override
	public String getType() {
		return type;
	}

	@Override
	public DetectionResponse setType(String type) {
		this.type = type;
		return this;
	}

	public String getLabel() {
		return label;
	}

	public DetectionResponse setLabel(String label) {
		this.label = label;
		return this;
	}

	@Override
	public Integer getFrameNumber() {
		return frameNumber;
	}

	@Override
	public DetectionResponse setFrameNumber(Integer frameNumber) {
		this.frameNumber = frameNumber;
		return this;
	}

	@Override
	public Float getBboxX() {
		return bboxX;
	}

	@Override
	public DetectionResponse setBboxX(Float bboxX) {
		this.bboxX = bboxX;
		return this;
	}

	@Override
	public Float getBboxY() {
		return bboxY;
	}

	@Override
	public DetectionResponse setBboxY(Float bboxY) {
		this.bboxY = bboxY;
		return this;
	}

	@Override
	public Float getBboxWidth() {
		return bboxWidth;
	}

	@Override
	public DetectionResponse setBboxWidth(Float bboxWidth) {
		this.bboxWidth = bboxWidth;
		return this;
	}

	@Override
	public Float getBboxHeight() {
		return bboxHeight;
	}

	@Override
	public DetectionResponse setBboxHeight(Float bboxHeight) {
		this.bboxHeight = bboxHeight;
		return this;
	}

	@Override
	public Float getConfidence() {
		return confidence;
	}

	@Override
	public DetectionResponse setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
	}

	public String getAssetUuid() {
		return assetUuid;
	}

	public DetectionResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public DetectionResponse self() {
		return this;
	}

}
