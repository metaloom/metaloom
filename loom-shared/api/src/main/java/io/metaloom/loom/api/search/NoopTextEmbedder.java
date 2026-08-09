package io.metaloom.loom.api.search;

import java.util.List;

import io.metaloom.loom.api.options.SearchOptions;

/**
 * The embedder bound when semantic search is off or its host could not be reached.
 *
 * <p>
 * It reports {@link #isAvailable()} false, which is the whole point: the provider then advertises neither {@code SEMANTIC} nor {@code HYBRID}, those
 * modes are rejected with a 400 naming the reason, and the UI's mode toggle stays hidden. Nothing degrades to a lexical answer wearing a semantic
 * label.
 * </p>
 *
 * <p>
 * {@link #embed(String)} throws rather than returning a zero vector. A zero vector is a real point in the space and would rank documents in a
 * meaningless but entirely plausible order - the exact failure the capability model exists to prevent.
 * </p>
 */
public class NoopTextEmbedder implements TextEmbedder {

	public static final String NAME = "none";

	private final String reason;
	private final VectorSpace space;

	public NoopTextEmbedder(String reason) {
		this.reason = reason;
		// A placeholder space: never written to and never queried, but non-null so callers that log the
		// configured space while diagnosing "why is semantic off" get an answer instead of an NPE.
		this.space = new VectorSpace(SearchOptions.DEFAULT_VECTOR_TYPE, "", SearchOptions.DEFAULT_EMBED_DIMENSIONS);
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public boolean isAvailable() {
		return false;
	}

	@Override
	public VectorSpace space() {
		return space;
	}

	public String reason() {
		return reason;
	}

	@Override
	public float[] embed(String text) {
		throw new IllegalStateException("Text embedding is not available: " + reason);
	}

	@Override
	public List<float[]> embedAll(List<String> texts) {
		throw new IllegalStateException("Text embedding is not available: " + reason);
	}
}
