package io.metaloom.cortex.api.node;

/**
 * A typed key for accessing values in a {@link NodeResult}'s output map.
 * Each key carries its value type, enabling type-safe access without casts.
 *
 * <p>Concrete node implementations declare their output keys as static constants:
 * <pre>{@code
 * public static final NodeOutputKey<String> OUTPUT_CAPTION = NodeOutputKey.of("caption_result", String.class);
 * }</pre>
 *
 * @param <T> the type of the value associated with this key
 */
public interface NodeOutputKey<T> {

	/**
	 * The string key used in the output map. This is also the key used for xattr/sidecar persistence.
	 */
	String key();

	/**
	 * The expected value type.
	 */
	Class<T> valueType();

	/**
	 * Create a new output key.
	 *
	 * @param key       the string key
	 * @param valueType the value class
	 * @param <T>       the value type
	 * @return a new output key instance
	 */
	static <T> NodeOutputKey<T> of(String key, Class<T> valueType) {
		return new NodeOutputKey<>() {
			@Override
			public String key() {
				return key;
			}

			@Override
			public Class<T> valueType() {
				return valueType;
			}

			@Override
			public String toString() {
				return key + "<" + valueType.getSimpleName() + ">";
			}

			@Override
			public int hashCode() {
				return key.hashCode();
			}

			@Override
			public boolean equals(Object obj) {
				if (this == obj) return true;
				if (obj instanceof NodeOutputKey<?> other) {
					return key.equals(other.key());
				}
				return false;
			}
		};
	}
}
