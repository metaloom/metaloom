package io.metaloom.cortex.api.node.context.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.artifact.ArtifactCache;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.loom.nodes.spec.ValueCoercer;
import io.metaloom.loom.nodes.spec.ValueCoercionException;
import io.metaloom.loom.pipeline.model.DataElement;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;

public class NodeContextImpl<I> implements NodeContext<I> {

	private final long start;
	private final I input;
	private final LoomMedia media;
	private final NodeInputs inputs;

	private String info;
	private ResultOrigin resultOrigin;
	private String skipReason;
	private String failureCause;

	/**
	 * One accumulator per output port, in emission order. Kept as a map of lists rather than of finished {@link PortOutput}s so that
	 * {@code outputElement} can append without rebuilding, and so that the emission order of a {@code MANY} port survives — the engine numbers the
	 * elements in exactly this order.
	 */
	private final Map<String, OutputPort<?>> outputPorts = new LinkedHashMap<>();
	private final Map<String, List<Object>> outputValues = new LinkedHashMap<>();

	@SuppressWarnings("unchecked")
	public NodeContextImpl(LoomMedia media) {
		this(media, NodeInputs.empty());
	}

	@SuppressWarnings("unchecked")
	public NodeContextImpl(LoomMedia media, NodeInputs inputs) {
		this.media = media;
		this.input = (I) media;
		this.start = System.currentTimeMillis();
		this.inputs = inputs != null ? inputs : NodeInputs.empty();
	}

	public NodeContextImpl(I input, LoomMedia media) {
		this.input = input;
		this.media = media;
		this.start = System.currentTimeMillis();
		this.inputs = NodeInputs.empty();
	}

	@Override
	public I input() {
		return input;
	}

	@Override
	public long duration() {
		return System.currentTimeMillis() - start;
	}

	@Override
	public LoomMedia media() {
		return media;
	}

	@Override
	public ArtifactCache artifacts() {
		// Never null - NodeInputs substitutes the no-op scope for a caller that has none.
		return inputs.artifacts();
	}

	@Override
	public NodeContext<I> info(String info) {
		this.info = info;
		return this;
	}

	// ── inputs ───────────────────────────────────────────────────────────

	@Override
	public <T> T input(InputPort<T> port) {
		PortPayload payload = inputs.get(port.id());
		if (payload == null || payload.isEmpty()) {
			return null;
		}
		return read(port, payload.single());
	}

	@Override
	public <T> Optional<T> optionalInput(InputPort<T> port) {
		return Optional.ofNullable(input(port));
	}

	@Override
	public <T> List<Element<T>> inputs(InputPort<T> port) {
		PortPayload payload = inputs.get(port.id());
		if (payload == null || payload.isEmpty()) {
			return List.of();
		}
		List<Element<T>> elements = new ArrayList<>(payload.size());
		// The wire keeps the elements seq-ordered; sorting again here would hide a
		// producer that failed to, and a zip against a sibling branch depends on it.
		for (DataElement element : payload.getElements()) {
			elements.add(new Element<>(element.getOrigin(), read(port, element.getValue())));
		}
		return Collections.unmodifiableList(elements);
	}

	@Override
	public Origin origin() {
		return inputs.origin();
	}

	@Override
	public boolean isWired(InputPort<?> port) {
		PortPayload payload = inputs.get(port.id());
		return payload != null && !payload.isEmpty();
	}

	@Override
	public boolean isDemanded(OutputPort<?> port) {
		return inputs.demandedOutputs().contains(port.id());
	}

	/**
	 * Coerce and type-check one incoming value.
	 *
	 * <p>
	 * The coercion runs again on the read side even though the producer already ran it: the JSON round trip in between re-narrows a {@code Long} that
	 * happens to fit in 32 bits back to an {@code Integer}, and this pass is what re-widens it. That is the whole reason a node never sees the wrong
	 * Java type any more.
	 * </p>
	 */
	private <T> T read(InputPort<T> port, Object raw) {
		Object coerced = ValueCoercer.coerce(port.id(), port.contentType(), raw);
		if (!port.valueType().isInstance(coerced)) {
			throw new ValueCoercionException(port.id(), port.contentType(),
				"expected " + port.valueType().getSimpleName() + ", got " + coerced.getClass().getSimpleName());
		}
		return port.valueType().cast(coerced);
	}

	// ── outputs ──────────────────────────────────────────────────────────

	@Override
	public <T> NodeContext<I> output(OutputPort<T> port, T value) {
		List<Object> values = accumulator(port);
		values.clear();
		values.add(coerce(port, value));
		return this;
	}

	@Override
	public <T> NodeContext<I> outputElement(OutputPort<T> port, T value) {
		accumulator(port).add(coerce(port, value));
		return this;
	}

	private List<Object> accumulator(OutputPort<?> port) {
		outputPorts.put(port.id(), port);
		return outputValues.computeIfAbsent(port.id(), id -> new ArrayList<>(1));
	}

	/**
	 * Coercing on write is what makes a type error land on the node that caused it, naming the port — instead of surfacing as an unchecked cast in a
	 * downstream consumer or as a non-encodable value that clears a whole persist batch.
	 */
	private static <T> Object coerce(OutputPort<T> port, T value) {
		return ValueCoercer.coerce(port.id(), port.contentType(), value);
	}

	@Override
	public Map<String, PortOutput> outputs() {
		if (outputPorts.isEmpty()) {
			return Map.of();
		}
		Map<String, PortOutput> result = new LinkedHashMap<>(outputPorts.size());
		outputPorts.forEach((id, port) -> result.put(id, new PortOutput(port, List.copyOf(outputValues.get(id)))));
		return Collections.unmodifiableMap(result);
	}

	@Override
	public NodeResult next() {
		if (skipReason != null) {
			// Outputs survive a skip on purpose. Emptying the map here used to throw away
			// exactly the diagnostics needed to understand why the node did not finish.
			return new NodeResult(null, ResultState.SKIPPED, duration(), skipReason, outputs());
		}
		return new NodeResult(null, ResultState.SUCCESS, duration(), null, outputs());
	}

	@Override
	public NodeResult abort() {
		return new NodeResult(null, ResultState.FAILED, duration(), failureCause, outputs());
	}

	@Override
	public NodeContext<I> print(String string, String string2) {
		return this;
	}

	@Override
	public NodeContext<I> origin(ResultOrigin resultOrigin) {
		this.resultOrigin = resultOrigin;
		return this;
	}

	@Override
	public ResultOrigin resultOrigin() {
		return resultOrigin;
	}

	@Override
	public NodeContext<I> failure(String cause) {
		this.failureCause = cause;
		return this;
	}

	@Override
	public NodeContext<I> skipped(String reason) {
		this.skipReason = reason;
		return this;
	}
}
