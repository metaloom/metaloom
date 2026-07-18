package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Provides node descriptors for deduplication output nodes (hash-dedup, fingerprint-dedup).
 */
public class DedupDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("hash-dedup")
				.setName("Hash Deduplication")
				.setDescription("Detect and move duplicate files based on hash equality.")
				.setIcon("file_copy")
				.setCategory(OUTPUT)
				.setInputs(List.of(new NodeInput("hash", DATA_HASH, true)))
				.setOutputs(List.of())
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("dupFolder").setType(STRING).setDefaultValue("duplicates")
						.setLabel("Duplicates Folder").setDescription("Target folder for duplicate files")))
				.setDefaultConcurrency(1)
				.setDefaultMode(SEQUENTIAL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("fingerprint-dedup")
				.setName("Fingerprint Deduplication")
				.setDescription("Detect and move near-duplicate files based on perceptual fingerprint similarity.")
				.setIcon("content_copy")
				.setCategory(OUTPUT)
				.setInputs(List.of(new NodeInput("fingerprint", DATA_FINGERPRINT, true)))
				.setOutputs(List.of())
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("dupFolder").setType(STRING).setDefaultValue("duplicates")
						.setLabel("Duplicates Folder").setDescription("Target folder for duplicate files")))
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
