package io.metaloom.loom.rest.model.detection;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Confirm a detection: the producer found something real here.
 *
 * <p>
 * The body is optional - confirming with no body at all means "right box, right class". It exists for the third answer a reviewer can give: the box is
 * correct but the class is not, which is a confirmation carrying a correction rather than a rejection.
 * </p>
 */
public class DetectionConfirmRequest implements RestRequestModel {

	private String correctedLabel;

	/**
	 * The class the reviewer says this actually is, or null to accept the producer's own label.
	 *
	 * <p>
	 * Stored alongside the original label rather than replacing it: what the model said is the training signal, and overwriting it would destroy the
	 * only record that it was wrong.
	 * </p>
	 */
	public String getCorrectedLabel() {
		return correctedLabel;
	}

	public DetectionConfirmRequest setCorrectedLabel(String correctedLabel) {
		this.correctedLabel = correctedLabel;
		return this;
	}

}
