package io.metaloom.loom.api.search;

import java.util.List;

/**
 * Turns text into a vector in one {@link VectorSpace}.
 *
 * <p>
 * This is the piece a face embedder can never be. {@code SearchMode.SEMANTIC} means "rank by what the query <i>means</i>", which requires embedding the
 * user's {@code q} into the same space as the indexed content - and a face vector has no text side, so no amount of face embeddings unlocks it. One
 * implementation embeds both the corpus and the query, which is what makes their distance meaningful.
 * </p>
 *
 * <p>
 * <b>The same embedder must produce both sides.</b> {@link #space()} is the contract that enforces it: the documents indexed under a space and the
 * queries answered against it carry the same {@code (type, model, dimensions)} triple, so a model change lands in a new space beside the old rather
 * than silently comparing vectors that mean different things. See {@link VectorSpace}.
 * </p>
 *
 * <p>
 * Implementations must never throw from {@link #name()} or {@link #isAvailable()} - the status route calls them precisely when the inference host is
 * down. {@link #embed(String)} may throw; callers must treat a failure as "semantic search is unavailable right now" and say so, never as "no results".
 * The two are opposite answers.
 * </p>
 *
 * @see VectorIndex the index the resulting vectors are queried through
 */
public interface TextEmbedder {

	/** Stable identifier for logs and status output, e.g. {@code openai-compatible} or {@code none}. */
	String name();

	/**
	 * Whether text can be embedded right now.
	 *
	 * <p>
	 * This gates the {@code SEMANTIC} and {@code HYBRID} capabilities, so a deployment with no inference host advertises neither and the UI never
	 * renders a mode that can only produce an error.
	 * </p>
	 */
	boolean isAvailable();

	/**
	 * The space this embedder writes and queries. Every vector it returns has {@code space().dimensions()} components.
	 */
	VectorSpace space();

	/**
	 * Embed one string.
	 *
	 * @throws RuntimeException
	 *             when the inference host cannot be reached or answers unusably. Never returns null and never returns a zero vector to paper over a
	 *             failure - a zero vector is a valid point in the space and would quietly rank as a real, arbitrary answer.
	 */
	float[] embed(String text);

	/**
	 * Embed a batch in one call.
	 *
	 * <p>
	 * Batching is why indexing a catalog is practical: per-request overhead dominates for short documents. The returned list is positionally aligned
	 * with the input, so callers can zip it back onto whatever they were embedding.
	 * </p>
	 */
	List<float[]> embedAll(List<String> texts);
}
