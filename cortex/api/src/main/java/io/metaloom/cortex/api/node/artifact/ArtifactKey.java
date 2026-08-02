package io.metaloom.cortex.api.node.artifact;

import java.util.Objects;

/**
 * Names an expensive intermediate artifact so that two nodes in one segment can mean the same thing by it.
 *
 * <p>
 * The key is a contract between node implementations, not a per-node private name — that is the whole point. The node that decodes the frames and the
 * node that reads them are different classes, often in different modules, and the key is the only thing they share. Declare it as a {@code public
 * static final} on whichever of them owns the definition and let the other import it.
 * </p>
 *
 * <h2>The id must encode everything that changes the artifact</h2>
 *
 * <p>
 * An artifact sampled at 2 fps and one sampled at 8 fps are different artifacts. If both are called {@code "keyframes"} the second node silently gets
 * the first node's sampling and nobody finds out until the numbers look odd. Put the parameters in the id:
 * </p>
 *
 * <pre>
 * ArtifactKey&lt;List&lt;Frame&gt;&gt; KEYFRAMES_2FPS = ArtifactKey.of("video/keyframes@2fps", List.class);
 * </pre>
 *
 * <p>
 * The {@code type} is checked on the way out, so a collision between two unrelated artifacts that happen to share an id fails with a
 * {@link ClassCastException} naming both rather than handing one node the other's object.
 * </p>
 *
 * @param <T>
 *            the artifact type
 */
public record ArtifactKey<T>(String id, Class<T> type) {

	public ArtifactKey {
		Objects.requireNonNull(id, "An artifact key needs an id");
		Objects.requireNonNull(type, "An artifact key needs a type - it is what stops two artifacts sharing an id from being confused");
		if (id.isBlank()) {
			throw new IllegalArgumentException("An artifact key id must not be blank");
		}
	}

	/**
	 * @param id
	 *            a stable name that encodes every parameter affecting the artifact's content
	 * @param type
	 *            the artifact's runtime type, used to check what comes back out of the cache
	 */
	public static <T> ArtifactKey<T> of(String id, Class<T> type) {
		return new ArtifactKey<>(id, type);
	}

	/**
	 * Cast a cached value back to this key's type.
	 *
	 * @throws ClassCastException
	 *             when two different artifacts were published under the same id
	 */
	public T cast(Object value) {
		if (value != null && !type.isInstance(value)) {
			throw new ClassCastException("Artifact '" + id + "' is cached as a " + value.getClass().getName()
				+ " but was requested as a " + type.getName() + ". Two artifacts are sharing one key id.");
		}
		return type.cast(value);
	}

	@Override
	public String toString() {
		return id + " (" + type.getSimpleName() + ")";
	}
}
