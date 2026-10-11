package io.metaloom.loom.db.model.person;

import java.util.UUID;

/**
 * One vector that says what a known person looks or sounds like, in one comparable space.
 *
 * <p>
 * Two kinds exist and person matching reads both: the centroid of a cluster a reviewer confirmed to be the person, and a print computed from one of
 * the person's own samples. Neither is copied into the other - a confirmed centroid is already stored on its cluster, and a sample has no cluster.
 * </p>
 *
 * @param personUuid
 *            who the vector describes
 * @param sourceUuid
 *            the cluster or the sample (attachment) it came from
 * @param source
 *            {@link #SOURCE_CLUSTER} or {@link #SOURCE_SAMPLE}
 * @param vector
 *            the vector; its space is the (type, model) it was read with
 */
public record PersonReference(UUID personUuid, UUID sourceUuid, String source, Float[] vector) {

	/** The centroid of a cluster a reviewer confirmed. */
	public static final String SOURCE_CLUSTER = "cluster";

	/** A print computed from a sample uploaded to, or imported into, the person. */
	public static final String SOURCE_SAMPLE = "sample";
}
