package io.metaloom.cortex.api.node.context;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.Element;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.artifact.ArtifactCache;
import io.metaloom.cortex.api.node.context.impl.NodeContextImpl;
import io.metaloom.loom.pipeline.model.Origin;

/**
 * Context for a single node invocation. Wraps the typed input {@code I} and accumulates metadata about the processing (origin, skip reason, timing,
 * port outputs).
 *
 * <p>
 * Data is addressed by <strong>port</strong>, never by node id. {@code upstreamOutput(nodeId, key)} is gone: it was keyed by a name the pipeline
 * author picked in the editor, erased its generic, and returned {@code null} for a typo — which every caller then read as "upstream produced nothing".
 * </p>
 *
 * @param <I>
 *            the input type
 */
public interface NodeContext<I> {

	static NodeContext<LoomMedia> create(LoomMedia media) {
		return new NodeContextImpl<>(media);
	}

	static NodeContext<LoomMedia> create(LoomMedia media, NodeInputs inputs) {
		return new NodeContextImpl<>(media, inputs);
	}

	/**
	 * Returns the typed input for this node invocation.
	 */
	I input();

	/**
	 * Returns the underlying {@link LoomMedia} (convenience accessor).
	 */
	LoomMedia media();

	/**
	 * Returns the time in milliseconds since the creation of this context.
	 */
	long duration();

	NodeContext<I> skipped(String reason);

	NodeContext<I> origin(ResultOrigin origin);

	/**
	 * Where the value came from — computed, read from a local cache, or fetched from Loom.
	 *
	 * <p>
	 * Named apart from {@link #origin()} because the two answer different questions: this one is a provenance flag for reporting, that one is the
	 * element identity the engine routes by.
	 * </p>
	 */
	ResultOrigin resultOrigin();

	NodeContext<I> failure(String cause);

	// ── inputs ───────────────────────────────────────────────────────────

	/**
	 * Read a {@code ONE} input port.
	 *
	 * @return the coerced value, or {@code null} when the port is optional and nothing was wired to it
	 * @throws io.metaloom.loom.nodes.spec.ValueCoercionException
	 *             when what arrived cannot satisfy the port's declared type
	 */
	<T> T input(InputPort<T> port);

	/**
	 * Read a {@code ONE} input port that may legitimately be absent.
	 */
	<T> Optional<T> optionalInput(InputPort<T> port);

	/**
	 * Read a {@code MANY} input port. The elements are seq-ordered and carry their origin, so two branches of the same fan-out can be zipped by
	 * {@link Element#seq()}.
	 *
	 * @return the elements, never null; empty when nothing was wired
	 */
	<T> List<Element<T>> inputs(InputPort<T> port);

	/**
	 * The origin of this execution — the run item and, for a per-element node, which element of the sequence it is processing.
	 *
	 * @return the origin, or null when the node is running outside a pipeline run
	 */
	Origin origin();

	/**
	 * Whether the pipeline wired anything into this input port.
	 *
	 * <p>
	 * This is how a node tells which alternative of an XOR group fed it — {@code whisper} asking whether it was handed audio or video.
	 * </p>
	 */
	boolean isWired(InputPort<?> port);

	/**
	 * The artifact scope shared by every node of this segment execution.
	 *
	 * <p>
	 * Where a node parks something expensive that the next node needs and that has no business being an output port — decoded frames, an extracted
	 * audio track, a parsed document. Never null: outside a managed execution it is {@link ArtifactCache#noop()}, so a node that uses it works
	 * standalone and simply pays the cost each time.
	 * </p>
	 *
	 * <pre>
	 * List&lt;Frame&gt; frames = ctx.artifacts().get(KEYFRAMES, () -&gt; {
	 *     List&lt;Frame&gt; decoded = decode(ctx.media(), 2.0);
	 *     return Artifact.of(decoded, decoded.size() * bytesPerFrame);
	 * });
	 * </pre>
	 *
	 * <p>
	 * Read {@link ArtifactCache} before publishing into it — the artifact must be treated as immutable, must not be retained past this
	 * {@code process()} call, and must be weighed honestly.
	 * </p>
	 */
	ArtifactCache artifacts();

	/**
	 * Whether anything downstream asked for this output port.
	 *
	 * <p>
	 * A hint, not a restriction: emitting an undemanded port stays legal and is still persisted, because that is what keeps a run's diagnostics useful.
	 * It exists so a node can skip genuinely expensive work nobody wants — not running the depth model when no edge leaves {@code map}.
	 * </p>
	 */
	boolean isDemanded(OutputPort<?> port);

	// ── outputs ──────────────────────────────────────────────────────────

	/**
	 * Emit the value of a {@code ONE} output port.
	 *
	 * @return this context for chaining
	 */
	<T> NodeContext<I> output(OutputPort<T> port, T value);

	/**
	 * Append one element to a {@code MANY} output port. The engine stamps {@code origin{itemId, seq, total}} at the boundary — a node only has to emit
	 * in order.
	 *
	 * @return this context for chaining
	 */
	<T> NodeContext<I> outputElement(OutputPort<T> port, T value);

	/**
	 * The accumulated outputs, keyed by output port id.
	 */
	Map<String, PortOutput> outputs();

	NodeResult next();

	NodeResult abort();

	NodeContext<I> print(String string, String string2);

	NodeContext<I> info(String msg);
}
