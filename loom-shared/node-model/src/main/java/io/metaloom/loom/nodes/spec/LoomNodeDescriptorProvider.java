package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Provides the node descriptor for the Loom sync output node.
 */
public class LoomNodeDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("loom")
				.setName("Loom Sync")
				.setDescription("Synchronize processing results back to the Loom backend.")
				.setIcon("cloud_upload")
				.setCategory(OUTPUT)
				.setInputs(List.of(new NodeInput("results", DATA_STRING, false)))
				.setOutputs(List.of())
				.setParameters(List.of(commonEnabled()))
				.setDefaultConcurrency(1)
				.setDefaultMode(PARALLEL)
				.setDefaultBlocking(false)
				.setEvents(STANDARD_EVENTS)
		);
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}
}
