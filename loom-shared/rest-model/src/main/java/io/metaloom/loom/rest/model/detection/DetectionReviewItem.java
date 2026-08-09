package io.metaloom.loom.rest.model.detection;

/**
 * One verdict inside a {@link DetectionBulkReviewRequest}.
 *
 * <p>
 * Unlike the single-detection {@code /confirm} and {@code /reject} routes, the verdict is a field here: a batch is a mixed set of decisions, and
 * splitting it into two requests would break the atomicity the reviewer expects from one pass over an asset.
 * </p>
 */
public class DetectionReviewItem {

	private String uuid;

	private String status;

	private String correctedLabel;

	/** The detection being decided on. Must belong to the asset in the path. */
	public String getUuid() {
		return uuid;
	}

	public DetectionReviewItem setUuid(String uuid) {
		this.uuid = uuid;
		return this;
	}

	/** {@code PENDING}, {@code CONFIRMED} or {@code REJECTED}. {@code PENDING} is accepted so a reviewer can undo a decision. */
	public String getStatus() {
		return status;
	}

	public DetectionReviewItem setStatus(String status) {
		this.status = status;
		return this;
	}

	/** See {@link DetectionConfirmRequest#getCorrectedLabel()}. */
	public String getCorrectedLabel() {
		return correctedLabel;
	}

	public DetectionReviewItem setCorrectedLabel(String correctedLabel) {
		this.correctedLabel = correctedLabel;
		return this;
	}

}
