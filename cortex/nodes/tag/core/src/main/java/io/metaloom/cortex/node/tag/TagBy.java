package io.metaloom.cortex.node.tag;

/**
 * How a {@link TagNode} instance decides which tags an item gets.
 *
 * <p>
 * This is the seam that keeps tagging one kind. Adding a way of tagging is a {@link TagStrategy}
 * implementation plus a Dagger binding plus a value here, and never an edit to {@link TagNode} — the
 * same shape as {@code FilterBy} on the filter node, and for the same reason: this palette already
 * carried eight {@code filter-*} kinds that could never run, and {@code tag-color} / {@code tag-llm}
 * / {@code tag-rules} would be that mistake a second time.
 * </p>
 */
public enum TagBy {

	/**
	 * Declarative predicates over the wired input ports. No model, no network, and the answer is
	 * reproducible from the pipeline definition alone.
	 */
	RULES,

	/**
	 * Every element on the {@code labels} port becomes a tag.
	 *
	 * <p>
	 * The terminal for the nodes that already produce tag-shaped strings — {@code dominant-color}'s
	 * colour names, {@code sentiment}'s label, a {@code filter} bucket, a {@code script} output. Use
	 * {@code allowedTags} with it: a label list from a model is an unbounded vocabulary, and tags are
	 * global.
	 * </p>
	 */
	LABELS
}
