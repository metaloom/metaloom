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
 * The {@code type} is part of the key's identity, not decoration. Two keys with the same id and different types are two different artifacts: each
 * node builds and gets its own, which is the safe outcome — nobody is handed an object of a type they did not ask for. What the type cannot save you
 * from is two artifacts of the <em>same</em> type differing only in how they were produced, which is why the parameters belong in the id.
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
	 * <p>
	 * Unreachable through a scope keyed by whole {@code ArtifactKey}s, which is the point: this is the invariant that lets the scope hand back a typed
	 * {@code T} without an unchecked cast, and it fails loudly rather than silently should a future implementation key by id alone.
	 * </p>
	 *
	 * @throws ClassCastException
	 *             when the cached value is not of this key's type
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
