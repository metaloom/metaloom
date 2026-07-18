package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Provides node descriptors for pipeline source nodes (Filesystem Source, Loom Fetch).
 */
public class SourceDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("filesystem-source")
				.setName("Filesystem Source")
				.setDescription("Reads media files from the filesystem as pipeline input.")
				.setIcon("folder_open")
				.setCategory(SOURCE)
				.setInputs(List.of())
				.setOutputs(List.of(new NodeOutput("media", MEDIA_ANY)))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("path").setType(STRING).setLabel("Path")
						.setDescription("Root directory to scan for media files")))
				.setDefaultConcurrency(1)
				.setDefaultMode(SEQUENTIAL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("loom-fetch")
				.setName("Loom Fetch")
				.setDescription("Fetches media references from the Loom backend.")
				.setIcon("cloud_download")
				.setCategory(SOURCE)
				.setInputs(List.of())
				.setOutputs(List.of(new NodeOutput("media", MEDIA_ANY)))
				.setParameters(List.of(commonEnabled()))
				.setDefaultConcurrency(1)
				.setDefaultMode(SEQUENTIAL)
				.setEvents(STANDARD_EVENTS)
		);
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}
}
