package io.metaloom.cortex.node.scenelayout;

import io.vertx.core.json.JsonObject;

/**
 * One asserted relation between two objects, with the numbers that produced it.
 *
 * <p>
 * The {@code evidence} is not decoration. These predicates come from thresholds over noisy
 * monocular depth, so "why did it say behind?" is a question that gets asked - and it should be
 * answerable from the stored payload without re-running anything.
 * </p>
 *
 * @param subjectId  the subject object's id
 * @param predicate  the asserted relation
 * @param objectId   the object object's id
 * @param confidence how strongly the evidence supports it, in [0, 1]
 * @param evidence   the measurements behind the assertion
 */
public record SpatialRelation(
	String subjectId,
	RelationPredicate predicate,
	String objectId,
	double confidence,
	JsonObject evidence) {

	/**
	 * Serialize to the persisted payload shape.
	 *
	 * @return the JSON form
	 */
	public JsonObject toJson() {
		return new JsonObject()
			.put("subject", subjectId)
			.put("predicate", predicate.name())
			.put("object", objectId)
			.put("confidence", round(confidence))
			.put("evidence", evidence);
	}

	private static double round(double value) {
		return Math.round(value * 1_000_000d) / 1_000_000d;
	}
}
