package io.metaloom.loom.rest.model.nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.nodes.spec.ContentType;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.rest.model.RestModel;

/**
 * What {@code GET /api/v1/pipeline/node-descriptors} returns: contracts, the content-type vocabulary,
 * and the fleet state alongside them.
 *
 * <p>
 * A real model rather than three hand-spliced {@code Json.encode} fragments, which is what this
 * response used to be. Appending a third block by string concatenation works right up until the commit
 * that forgets a comma.
 * </p>
 */
public class NodeDescriptorsResponse implements RestModel {

	@JsonPropertyDescription("Every node contract Loom knows, built-in and announced")
	private List<NodeDescriptor> nodeDescriptors = new ArrayList<>();

	@JsonPropertyDescription("The content-type vocabulary, including types synthesized from announced ports")
	private List<ContentType> contentTypes = new ArrayList<>();

	/**
	 * Fleet state per node id.
	 *
	 * <p>
	 * Omitted entirely by the checked-in {@code node-descriptors.json} snapshot the offline website
	 * editor reads. A missing block, and a missing entry within it, must be read as "available" — that
	 * editor has no fleet, and every node in it is authorable.
	 * </p>
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("Runtime state keyed by node id. Absent means 'no fleet information', which reads as available")
	private Map<String, NodeAvailability> availability = new LinkedHashMap<>();

	public List<NodeDescriptor> getNodeDescriptors() {
		return nodeDescriptors;
	}

	public NodeDescriptorsResponse setNodeDescriptors(List<NodeDescriptor> nodeDescriptors) {
		this.nodeDescriptors = nodeDescriptors;
		return this;
	}

	public List<ContentType> getContentTypes() {
		return contentTypes;
	}

	public NodeDescriptorsResponse setContentTypes(List<ContentType> contentTypes) {
		this.contentTypes = contentTypes;
		return this;
	}

	public Map<String, NodeAvailability> getAvailability() {
		return availability;
	}

	public NodeDescriptorsResponse setAvailability(Map<String, NodeAvailability> availability) {
		this.availability = availability;
		return this;
	}
}
