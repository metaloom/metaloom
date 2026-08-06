package io.metaloom.loom.rest.model.vector;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * State of the embedding vector index.
 *
 * <p>
 * {@code enabled} and {@code available} are separate on purpose: a provider that is configured but failed to open is enabled and unavailable, which is
 * an operational problem, while one that was never switched on is neither, which is not. Collapsing them into a single boolean would make a broken
 * index look like a deliberately disabled one.
 * </p>
 */
public class VectorIndexStatusResponse implements RestResponseModel<VectorIndexStatusResponse> {

	private String provider;

	private boolean enabled;

	private boolean available;

	private long indexed;

	/** The bound backend, e.g. {@code lucene} or {@code none}. */
	public String getProvider() {
		return provider;
	}

	public VectorIndexStatusResponse setProvider(String provider) {
		this.provider = provider;
		return this;
	}

	/** Whether a backend is configured at all ({@code LOOM_VECTOR_INDEX_PROVIDER} is not {@code none}). */
	public boolean isEnabled() {
		return enabled;
	}

	public VectorIndexStatusResponse setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	/** Whether the configured backend actually opened and can answer queries. */
	public boolean isAvailable() {
		return available;
	}

	public VectorIndexStatusResponse setAvailable(boolean available) {
		this.available = available;
		return this;
	}

	/** How many vectors the operation just wrote. Zero for a plain status read. */
	public long getIndexed() {
		return indexed;
	}

	public VectorIndexStatusResponse setIndexed(long indexed) {
		this.indexed = indexed;
		return this;
	}

	@Override
	public VectorIndexStatusResponse self() {
		return this;
	}

}
