package io.metaloom.cortex.api.node.artifact;

import java.util.Objects;

/**
 * An artifact together with the producer's estimate of what it costs to keep.
 *
 * <p>
 * The weight is mandatory and it is the producing node's job, because nothing else can work it out. A {@code List<Mat>} of decoded frames measures a
 * handful of bytes by any generic heap-walking estimate and holds two hundred megabytes of native memory; a cache that guessed would either evict
 * nothing or evict everything. The node that decoded the frames knows {@code width * height * channels * count} and can say so in one line.
 * </p>
 *
 * <p>
 * An over-estimate costs an eviction that was not needed. An under-estimate is how a worker runs out of memory. Round up.
 * </p>
 *
 * <p>
 * When the value implements {@link AutoCloseable} the scope closes it at the end of the segment — which is the reason native-backed artifacts are
 * safe to cache at all. See {@link ArtifactCache} for exactly when that happens and when it deliberately does not.
 * </p>
 *
 * @param <T>
 *            the artifact type
 */
public record Artifact<T>(T value, long weightBytes) {

	public Artifact {
		Objects.requireNonNull(value, "An artifact must not be null - a factory with nothing to publish must throw instead");
		if (weightBytes < 0) {
			throw new IllegalArgumentException("An artifact's weight must not be negative: " + weightBytes);
		}
	}

	/**
	 * @param value
	 *            the artifact
	 * @param weightBytes
	 *            what holding it costs, in bytes, rounded up — including memory the JVM heap does not account for
	 */
	public static <T> Artifact<T> of(T value, long weightBytes) {
		return new Artifact<>(value, weightBytes);
	}
}
