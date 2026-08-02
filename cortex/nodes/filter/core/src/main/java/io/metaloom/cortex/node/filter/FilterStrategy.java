package io.metaloom.cortex.node.filter;

import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.vertx.core.json.JsonObject;

/**
 * Decides which bucket an item belongs to.
 *
 * <p>
 * One implementation per {@link FilterBy} value, bound into a map so {@link FilterNode} never needs
 * to know which ones exist. That is what keeps "add a way of filtering" a strictly additive change
 * rather than another node kind in an already large palette.
 * </p>
 */
public interface FilterStrategy {

	/** The {@code filterBy} value this strategy serves. */
	FilterBy filterBy();

	/**
	 * Classify one item.
	 *
	 * @param ctx
	 *            the node context, carrying the media
	 * @param options
	 *            the node's per-instance configuration
	 * @param buckets
	 *            the configured buckets, already validated
	 * @param text
	 *            the text wired into the node's {@code text} port; may be null or blank
	 * @return the outcome; never null. Return {@link Classification#other(String)} rather than
	 *         throwing when the answer is merely unusable — {@code other} is what that branch is
	 *         for. Throw only when the strategy genuinely could not do its job, which fails the task
	 * @throws Exception
	 *             when the backing service is unreachable or errors
	 */
	Classification classify(NodeContext<LoomMedia> ctx, FilterNodeOptions options, List<FilterBucket> buckets, String text) throws Exception;

	/**
	 * Where an item was routed, and why.
	 *
	 * @param bucketId
	 *            the chosen bucket id, or {@code "other"}
	 * @param confidence
	 *            0..1; {@code 0} when the strategy has no notion of confidence
	 * @param detail
	 *            extra fields for the persisted component — the raw answer, the model used
	 */
	record Classification(String bucketId, double confidence, JsonObject detail) {

		/** The catch-all port id. Kept in step with {@code FilterPortResolver.PORT_OTHER}. */
		public static final String OTHER = "other";

		public static Classification of(String bucketId, double confidence, JsonObject detail) {
			return new Classification(bucketId, confidence, detail == null ? new JsonObject() : detail);
		}

		public static Classification other(String reason) {
			return new Classification(OTHER, 0, new JsonObject().put("reason", reason));
		}

		public boolean isOther() {
			return OTHER.equals(bucketId);
		}
	}
}
