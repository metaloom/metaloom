package io.metaloom.cortex.api.node.context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
import io.metaloom.loom.pipeline.model.NodePreview;
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

	/**
	 * Record why this item could not be processed.
	 *
	 * <p>
	 * Terminate with {@link #abort()}. The cause is the node's diagnosis and {@code abort()} is the
	 * statement that the item failed — writing them together keeps the intent in the node rather than
	 * in the context. {@code failure(cause).next()} used to build a <em>SUCCESS</em> result and discard
	 * the cause; it now yields {@code FAILED} like {@code abort()}, but it is still the wrong shape and
	 * {@code FailurePathGuardTest} fails the build on it.
	 * </p>
	 *
	 * <p>
	 * A failure is a real error an operator must see. "This item is not applicable" is
	 * {@link #skipped(String)}, which is a different outcome and reads as one in the ledger.
	 * </p>
	 */
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
	 * The pipeline run this execution belongs to. This is what lets a ledger row ({@code asset_node_result}) name the run that produced it — the join
	 * every invalidation sweep starts from.
	 *
	 * @return the run uuid, or null when the node is running outside a pipeline run
	 */
	UUID runUuid();

	/**
	 * The pipeline node task this execution answers.
	 *
	 * @return the task uuid, or null when the node is running outside a pipeline run
	 */
	UUID taskUuid();

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
	 * Describe what a port carried, in Markdown, for whoever is debugging this run.
	 *
	 * <p>
	 * Entirely optional. The editor already renders every port from its declared content type, and
	 * that default is right for most of them. This exists for the cases where a node knows something
	 * the type system does not - that these four numbers are a bounding box, that this array is one
	 * row per detected face. A GFM table says so; the raw JSON does not.
	 * </p>
	 *
	 * <pre>
	 * ctx.preview(OUT_DETECTIONS, "| # | score | box |\n|---|---|---|\n" + rows);
	 * </pre>
	 *
	 * <p>
	 * Cheap to call and safe to call unconditionally: the string is discarded at the result boundary
	 * unless the run asked for previews, so a production run pays for building it and nothing more.
	 * Prefer {@link #isDemanded(OutputPort)}-style guards only if the Markdown is genuinely expensive
	 * to produce.
	 * </p>
	 *
	 * @return this context for chaining
	 */
	NodeContext<I> preview(OutputPort<?> port, String markdown);

	/**
	 * Attach a prepared preview — an image, Markdown, or a recorded reason for having neither — to a
	 * port.
	 *
	 * <p>
	 * Images produced <em>by</em> a node are already picked up automatically: any {@code artifact/image}
	 * or {@code media/image} port whose value is a readable local path is downsampled at the result
	 * boundary without the node doing anything. This overload is for the other case — a node that can
	 * show something it never wrote to disk and never emits on a port. Face detection is the example:
	 * the frame it looked at and the faces it cut out of it exist only in memory.
	 * </p>
	 *
	 * <p>
	 * Unlike the Markdown overload this one is <strong>not</strong> free to call, because building the
	 * image happens before the call. Guard it with {@link #capturePreviews()}.
	 * </p>
	 *
	 * @return this context for chaining
	 */
	NodeContext<I> preview(OutputPort<?> port, NodePreview preview);

	/**
	 * Attach a preview to one <em>element</em> of a {@code MANY} port.
	 *
	 * <p>
	 * A port-level preview can only ever show one thing, which for a fanned-out port means the first
	 * element and nothing else. That is the right default for a list of numbers and useless for a list
	 * of faces, where each element is a picture in its own right. Elements previewed here are keyed
	 * {@code portId#seq} and travel beside the port-level entry rather than replacing it — so face
	 * detection can offer the whole frame <em>and</em> a crop per face.
	 * </p>
	 *
	 * <p>
	 * {@code elementSeq} is the index within this port's emission order, the same one
	 * {@link #outputElement(OutputPort, Object)} assigns.
	 * </p>
	 *
	 * @return this context for chaining
	 */
	NodeContext<I> preview(OutputPort<?> port, int elementSeq, NodePreview preview);

	/**
	 * Whether this run wants previews at all — {@code true} only for a run started in debug mode.
	 *
	 * <p>
	 * Check this before doing work that exists <em>only</em> to produce a preview. Everything attached
	 * without it is discarded at the result boundary anyway, so a production run never ships preview
	 * bytes either way; the difference is whether it pays to build them first.
	 * </p>
	 */
	boolean capturePreviews();

	/**
	 * The accumulated outputs, keyed by output port id.
	 */
	Map<String, PortOutput> outputs();

	/**
	 * Node-authored previews, keyed by output port id — or by {@code portId#seq} for a preview attached
	 * to a single element. Empty unless {@link #preview} was called.
	 */
	Map<String, NodePreview> previews();

	/**
	 * Finish this item and let the pipeline continue.
	 *
	 * <p>
	 * Yields {@code SUCCESS}, or {@code SKIPPED} when {@link #skipped(String)} recorded a reason. If
	 * {@link #failure(String)} recorded a cause it yields {@code FAILED} carrying that cause — a
	 * fail-closed backstop, not a supported spelling of {@link #abort()}.
	 * </p>
	 */
	NodeResult next();

	/**
	 * Finish this item as {@code FAILED}, carrying whatever {@link #failure(String)} recorded and
	 * whatever the node managed to emit before it broke. This is the failure terminator.
	 */
	NodeResult abort();

	NodeContext<I> print(String string, String string2);

	NodeContext<I> info(String msg);
}
