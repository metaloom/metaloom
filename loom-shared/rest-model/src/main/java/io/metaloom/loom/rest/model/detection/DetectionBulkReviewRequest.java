package io.metaloom.loom.rest.model.detection;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Record many verdicts on one asset in a single request.
 *
 * <p>
 * Not a convenience. Detection review is a keyboard loop - confirm, next, confirm, reject, next - and it outruns per-item HTTP badly enough that the
 * UI would either lag behind the reviewer or drop decisions. Batching is what makes the review screen usable, so it is part of the contract rather
 * than an optimisation.
 * </p>
 */
public class DetectionBulkReviewRequest implements RestRequestModel {

	private List<DetectionReviewItem> reviews = new ArrayList<>();

	public List<DetectionReviewItem> getReviews() {
		return reviews;
	}

	public DetectionBulkReviewRequest setReviews(List<DetectionReviewItem> reviews) {
		this.reviews = reviews;
		return this;
	}

	public DetectionBulkReviewRequest add(DetectionReviewItem review) {
		this.reviews.add(review);
		return this;
	}

}
