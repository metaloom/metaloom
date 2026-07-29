package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the Tika metadata extraction node.
 */
public class TikaDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("tika")
				.setName("Tika Extraction")
				.setDescription("Extract metadata and text content using Apache Tika.")
				.setIcon("description")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The document or container file to parse")))
				.setOutputPorts(List.of(
					one("content", TEXT_PLAIN)
						.describedAs("Content", "The document body Tika extracted, with the markup stripped out"),
					one("flags", SCALAR_STRING)
						.describedAs("Flags", "Processing markers recording which parsers Tika ended up using")))
				.setParameters(List.of(commonEnabled(), commonProcessIncomplete(), commonRetryFailed()))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS)
		);
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}

	private static NodeParameter commonProcessIncomplete() {
		return new NodeParameter().setKey("processIncomplete").setType(BOOLEAN).setDefaultValue(false)
			.setLabel("Process Incomplete").setDescription("Process media files that are still being written");
	}

	private static NodeParameter commonRetryFailed() {
		return new NodeParameter().setKey("retryFailed").setType(BOOLEAN).setDefaultValue(false)
			.setLabel("Retry Failed").setDescription("Retry processing media that previously failed");
	}
}
