package io.metaloom.loom.db.model.share;

/**
 * What a {@link ShareAnnotation} marks.
 *
 * <p>
 * The database CHECK in V2.99 requires each kind to actually carry the geometry it names, so {@link #requiresTime()} and {@link #requiresArea()}
 * are the Java-side statement of the same rule - validate against them before writing, or the insert fails with a constraint name instead of a
 * message a person can act on.
 * </p>
 */
public enum ShareAnnotationKind {

	/** A moment or a range on the timeline: "the cut at 0:14 is early". */
	TEMPORAL(true, false),

	/** A region of the frame: "this logo". */
	SPATIAL(false, true),

	/** A region, for a stretch of time: "this logo, between 0:14 and 0:19". */
	SPATIOTEMPORAL(true, true);

	private final boolean requiresTime;
	private final boolean requiresArea;

	ShareAnnotationKind(boolean requiresTime, boolean requiresArea) {
		this.requiresTime = requiresTime;
		this.requiresArea = requiresArea;
	}

	public boolean requiresTime() {
		return requiresTime;
	}

	public boolean requiresArea() {
		return requiresArea;
	}

	public static ShareAnnotationKind parse(String value) {
		if (value == null) {
			return null;
		}
		for (ShareAnnotationKind kind : values()) {
			if (kind.name().equals(value)) {
				return kind;
			}
		}
		return null;
	}
}
