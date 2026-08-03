package io.metaloom.loom.rest.model.processor.message;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.rest.model.RestModel;

/**
 * The {@code NODE_REGISTRATION} frame: a worker telling Loom what its nodes look like.
 *
 * <p>
 * A node dropped onto a worker's classpath used to be <em>runnable but unauthorable</em> — dispatch
 * knew its name from the {@code REGISTER} whitelist, but its ports and parameters lived only in
 * Loom's own jar, so the editor could not place it and the graph parser rejected it as unknown. This
 * frame carries the missing half.
 * </p>
 *
 * <p>
 * The elements are plain {@link NodeDescriptor}s — the exact type Loom already serves from
 * {@code /api/v1/pipeline/node-descriptors} and validates graphs against. There is deliberately no
 * separate registration DTO: one contract type, in both directions.
 * </p>
 *
 * <pre>{@code
 * { "type": "NODE_REGISTRATION",
 *   "body": { "cortexId": "cortex-gpu-01",
 *             "nodes": [ { "nodeId": "acme-nsfw", "version": "1.0.0-SNAPSHOT", ... } ] } }
 * }</pre>
 */
public class NodeRegistration implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The announcing worker's stable id - the same value it registered with. Loom verifies "
		+ "it matches this socket's identity; a worker may not speak for another worker.")
	private String cortexId;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The complete set of node contracts this worker offers. There is no delta form: a node "
		+ "absent from a later frame is unlinked from this worker.")
	private List<NodeDescriptor> nodes = new ArrayList<>();

	public NodeRegistration() {
	}

	public NodeRegistration(String cortexId, List<NodeDescriptor> nodes) {
		this.cortexId = cortexId;
		this.nodes = nodes;
	}

	public String getCortexId() {
		return cortexId;
	}

	public NodeRegistration setCortexId(String cortexId) {
		this.cortexId = cortexId;
		return this;
	}

	public List<NodeDescriptor> getNodes() {
		return nodes;
	}

	public NodeRegistration setNodes(List<NodeDescriptor> nodes) {
		this.nodes = nodes;
		return this;
	}
}
