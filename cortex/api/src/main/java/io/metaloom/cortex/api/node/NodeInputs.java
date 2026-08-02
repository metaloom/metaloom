package io.metaloom.cortex.api.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.cortex.api.node.artifact.ArtifactCache;
import io.metaloom.loom.pipeline.model.DataElement;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;

/**
 * Everything the engine hands a node besides the media itself: what its input ports carry, which of its output ports the pipeline actually wired up,
 * the origin this execution belongs to, and the scope it may park expensive intermediates in.
 *
 * <p>
 * This replaces the {@code Map<String, Map<String, Object>>} keyed by upstream node id. The keys here are <strong>this node's own input port ids</strong>
 * — the engine has already resolved which upstream {@code (node, port)} fills each one — so a node can no longer be broken by someone renaming its
 * neighbour in the editor.
 * </p>
 *
 * <p>
 * {@link #artifacts()} is the one component that is not data: it is the segment-scoped {@link ArtifactCache} through which a node hands a decoded
 * frame set or an extracted audio track to the next node in the same segment. Ports carry what is serialised back to Loom; the artifact scope carries
 * what must never be. It is never null — a node invoked outside a managed execution gets {@link ArtifactCache#noop()}, which computes and retains
 * nothing.
 * </p>
 */
public record NodeInputs(Map<String, PortPayload> ports, Set<String> demandedOutputs, Origin origin, ArtifactCache artifacts) {

	private static final NodeInputs EMPTY = new NodeInputs(Map.of(), Set.of(), null);

	public NodeInputs {
		ports = ports == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ports));
		demandedOutputs = demandedOutputs == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(demandedOutputs));
		artifacts = artifacts == null ? ArtifactCache.noop() : artifacts;
	}

	/**
	 * The form for a caller that has no artifact scope to offer — which is every caller outside the two task runners.
	 */
	public NodeInputs(Map<String, PortPayload> ports, Set<String> demandedOutputs, Origin origin) {
		this(ports, demandedOutputs, origin, null);
	}

	/**
	 * The same inputs, bound to an artifact scope. Used by the runner, which builds the port view per node but opens the scope once per segment.
	 */
	public NodeInputs withArtifacts(ArtifactCache artifacts) {
		return new NodeInputs(ports, demandedOutputs, origin, artifacts);
	}

	/**
	 * Nothing wired, nothing demanded — what a node gets when it is invoked outside a pipeline run.
	 *
	 * <p>
	 * Note that "nothing demanded" must not be read as "compute nothing": {@code isDemanded} is an optimisation hint, and a node invoked standalone is
	 * expected to do its full work.
	 * </p>
	 */
	public static NodeInputs empty() {
		return EMPTY;
	}

	/**
	 * The inputs for a standalone invocation over the given item.
	 */
	public static NodeInputs of(Map<String, PortPayload> ports) {
		return new NodeInputs(ports, Set.of(), null);
	}

	public PortPayload get(String portId) {
		return ports.get(portId);
	}

	/**
	 * Assemble a port view by hand.
	 *
	 * <p>
	 * The engine builds these from wired edges; this is for everything that has no engine behind it —
	 * a node invoked directly, a harness, a test. Going through the declared {@link InputPort} rather
	 * than through raw ids is what keeps such a caller from inventing a port the node does not have.
	 * </p>
	 */
	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private final Map<String, PortPayload> ports = new LinkedHashMap<>();
		private final Set<String> demanded = new LinkedHashSet<>();
		private Origin origin;

		private Builder() {
		}

		/** Fill a {@code ONE} port. */
		public <T> Builder input(InputPort<T> port, T value) {
			ports.put(port.id(), PortPayload.one(port.contentType(), originOrDefault(), value));
			return this;
		}

		/** Fill a {@code MANY} port; the elements are numbered in the order given. */
		public <T> Builder inputs(InputPort<T> port, List<T> values) {
			List<DataElement> elements = new ArrayList<>(values.size());
			for (int seq = 0; seq < values.size(); seq++) {
				elements.add(DataElement.of(Origin.of(itemId(), seq, values.size()), values.get(seq)));
			}
			ports.put(port.id(), PortPayload.many(port.contentType(), elements));
			return this;
		}

		/** Mark output ports as wired downstream. */
		public Builder demanded(OutputPort<?>... outputs) {
			for (OutputPort<?> output : outputs) {
				demanded.add(output.id());
			}
			return this;
		}

		public Builder origin(Origin origin) {
			this.origin = origin;
			return this;
		}

		public NodeInputs build() {
			return new NodeInputs(ports, demanded, origin);
		}

		private Origin originOrDefault() {
			return origin != null ? origin : Origin.single(itemId());
		}

		private String itemId() {
			return origin != null ? origin.getItemId() : "standalone";
		}
	}
}
