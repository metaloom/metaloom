package io.metaloom.cortex.node.filter;

import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nullable;

import io.metaloom.loom.rest.model.cluster.ClusterResponse;

/**
 * One known person named by one cluster of an asset, and how they are known to be there.
 *
 * @param personUuid
 *            who
 * @param name
 *            the name the person is shown by, or null when they have none
 * @param source
 *            {@link PersonSource#FACE} or {@link PersonSource#VOICE}
 * @param confirmed
 *            {@code true} for a reviewer's verdict, {@code false} for a suggestion
 * @param score
 *            the match score behind a suggestion; null for a verdict
 */
public record PersonMention(UUID personUuid, @Nullable String name, PersonSource source, boolean confirmed, @Nullable Float score) {

	private static final String TYPE_FACE = "face";

	private static final String TYPE_SPEAKER = "speaker";

	private static final String STATUS_CONFIRMED = "CONFIRMED";

	private static final String STATUS_REJECTED = "REJECTED";

	/**
	 * Who a cluster names, or null when it names nobody.
	 *
	 * <p>
	 * A confirmed cluster names its person and nobody else - when a reviewer said otherwise than the suggestion, the reviewer wins. A rejected cluster
	 * names nobody, even if it still carries a pointer: rejection keeps an earlier attribution readable, but says the cluster is not to be acted on.
	 * A pending cluster names its suggestion, if it has one. Clusters of other types (neither face nor speaker) are not about people.
	 * </p>
	 */
	@Nullable
	public static PersonMention of(ClusterResponse cluster) {
		PersonSource source = sourceOf(cluster.getType());
		if (source == null || STATUS_REJECTED.equals(cluster.getReviewStatus())) {
			return null;
		}
		if (STATUS_CONFIRMED.equals(cluster.getReviewStatus())) {
			UUID person = parse(cluster.getPersonUuid());
			return person == null ? null : new PersonMention(person, cluster.getPersonName(), source, true, null);
		}
		UUID suggested = parse(cluster.getSuggestedPersonUuid());
		return suggested == null ? null : new PersonMention(suggested, cluster.getSuggestedPersonName(), source, false, cluster.getSuggestedScore());
	}

	@Nullable
	static PersonSource sourceOf(String clusterType) {
		if (clusterType == null) {
			return null;
		}
		return switch (clusterType.toLowerCase(Locale.ROOT)) {
			case TYPE_FACE -> PersonSource.FACE;
			case TYPE_SPEAKER -> PersonSource.VOICE;
			default -> null;
		};
	}

	@Nullable
	private static UUID parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
