package io.metaloom.cortex.api.node.artifact;

import java.util.Optional;

import io.metaloom.cortex.api.node.artifact.impl.NoOpArtifactCache;

/**
 * Somewhere for a node to put an expensive intermediate artifact so that a later node in the same segment can use it instead of building it again.
 *
 * <p>
 * The problem it solves: a node receives its upstream dependencies' <em>outputs</em> and nothing else, and those outputs are serialised back to Loom.
 * That is the right home for a hash and the wrong home for a two-hundred-megabyte frame buffer, so five nodes that all need decoded frames decode the
 * file five times. Affinity segments already put those nodes in one process; this is the API that lets them share.
 * </p>
 *
 * <h2>The four questions</h2>
 *
 * <p>
 * <strong>Who owns it.</strong> The <em>segment execution</em> — not the node instance and not the worker. A node instance outlives the item (the
 * registry reuses it), so a cache on the node would have to be invalidated by hand on every item, and a cache invalidated by hand is a cache that
 * leaks. A worker-wide cache is the cross-run cache this deliberately is not. The segment execution is the one scope whose start and end are both
 * known, so it is the one scope that can be closed.
 * </p>
 *
 * <p>
 * <strong>Its lifecycle.</strong> One scope per {@code run()} of one segment over one item. It is created before the first node and
 * {@link #close() closed} after the last, closing every {@link AutoCloseable} artifact still in it. Nothing survives to the next item because nothing
 * survives at all — item B's nodes are handed a different object. A node that stashes the scope in a field and reaches for it on the next item gets
 * an {@link IllegalStateException} rather than a stale artifact.
 * </p>
 *
 * <p>
 * <strong>Failure and retry.</strong> A factory that throws publishes nothing. Beyond that, the runner wraps each node in a {@link Publication}: what
 * a node published is kept when that node succeeds and <em>discarded</em> when it fails. The conservative direction is chosen deliberately. A node
 * that dies halfway may have published an object it had not finished filling, and nothing in the type system distinguishes that from a complete one;
 * paying one recomputation in a rare path beats feeding a half-built artifact to every node after it. A retry after a lease expiry is a new
 * {@code run()} and therefore a new scope, so a retry can never repeat a failure by inheriting the state that caused it.
 * </p>
 *
 * <p>
 * <strong>What changes in {@code PipelineNode}.</strong> Nothing. The scope arrives on {@code NodeInputs}, which every node already receives, and a
 * node that does not call {@link #get} is unaffected. Opting in is one call.
 * </p>
 *
 * <h2>Using it</h2>
 *
 * <pre>
 * private static final ArtifactKey&lt;List&lt;Frame&gt;&gt; KEYFRAMES = ArtifactKey.of("video/keyframes@2fps", List.class);
 *
 * List&lt;Frame&gt; frames = ctx.artifacts().get(KEYFRAMES, () -&gt; {
 *     List&lt;Frame&gt; decoded = decode(ctx.media(), 2.0);
 *     return Artifact.of(decoded, decoded.size() * bytesPerFrame);
 * });
 * </pre>
 *
 * <h2>Rules for the artifact itself</h2>
 *
 * <ul>
 * <li><strong>Treat it as immutable.</strong> The next node gets the same object, not a copy. Mutating it changes what everyone downstream sees, and
 * nothing here can stop you.</li>
 * <li><strong>Do not retain it past {@code process()}.</strong> It is valid for the requesting node's execution. After the segment ends it may be
 * closed underneath you.</li>
 * <li><strong>Weigh it honestly.</strong> {@link Artifact#weightBytes()} is what keeps a segment inside its memory ceiling.</li>
 * </ul>
 *
 * <p>
 * Note what this is not. {@code LocalResultCache} remembers a node's finished <em>result</em> across items so a second pass skips the work, and its
 * durable copy lives in Loom. This holds an <em>intermediate</em> that was never a result and is never persisted. They do not overlap and neither
 * replaces the other.
 * </p>
 */
public interface ArtifactCache extends AutoCloseable {

	/**
	 * Return the artifact for this key, building it with the factory if no node in this segment has built it yet.
	 *
	 * <p>
	 * The factory runs at most once per key per segment. A concurrent caller for the same key waits rather than starting a second identical decode —
	 * which is the point, since the second decode is exactly the cost being removed.
	 * </p>
	 *
	 * @throws ArtifactException
	 *             when the factory fails; nothing is published and the next caller may try again
	 * @throws ClassCastException
	 *             when something else is already cached under this key's id
	 * @throws IllegalStateException
	 *             when the scope has already been closed — a node held on to it past its segment
	 */
	<T> T get(ArtifactKey<T> key, ArtifactFactory<T> factory);

	/**
	 * Look for an artifact without building it.
	 *
	 * <p>
	 * For a node that can do something cheaper when the artifact happens to be there but must not be the one to pay for it.
	 * </p>
	 */
	<T> Optional<T> peek(ArtifactKey<T> key);

	/**
	 * Drop an artifact, closing it when it is {@link AutoCloseable}.
	 *
	 * <p>
	 * For a node that knows the artifact has stopped being valid. Only call it when nothing else is still using the artifact — within a segment the
	 * nodes run in order, so "after mine, before the next one asks" is the safe window.
	 * </p>
	 */
	void invalidate(ArtifactKey<?> key);

	/**
	 * The sum of {@link Artifact#weightBytes()} currently retained.
	 */
	long retainedBytes();

	/**
	 * The ceiling this scope evicts against, or {@link Long#MAX_VALUE} when it does not retain anything.
	 */
	long maxBytes();

	/**
	 * Open the publication window for one node's execution.
	 *
	 * <p>
	 * Called by the runner, not by nodes. Artifacts first published inside the window are kept only if {@link Publication#commit()} is called — which
	 * the runner does when the node succeeds. Artifacts an <em>earlier</em> node published are untouched either way: the window records what this node
	 * added, not what it read.
	 * </p>
	 */
	Publication beginPublication();

	/**
	 * End the scope: close every {@link AutoCloseable} artifact still held and refuse further use.
	 *
	 * <p>
	 * Idempotent, and never throws for an artifact whose own {@code close()} failed — one leaky artifact must not prevent the rest from being
	 * released.
	 * </p>
	 */
	@Override
	void close();

	/**
	 * One node's publication window. Closing without committing discards what that node published.
	 */
	interface Publication extends AutoCloseable {

		/**
		 * Keep what this node published.
		 */
		void commit();

		@Override
		void close();
	}

	/**
	 * A scope that computes but never retains.
	 *
	 * <p>
	 * What a node gets when it runs outside any managed execution — a test, a CLI invocation, {@code NodeInputs.empty()}. Calling {@link #get} still
	 * returns the artifact, so a node written against this API works standalone; it simply pays for it every time, which is the correct behaviour
	 * when there is no scope to bound the retention. It never closes an artifact, because it never owned one.
	 * </p>
	 */
	static ArtifactCache noop() {
		return NoOpArtifactCache.INSTANCE;
	}
}
