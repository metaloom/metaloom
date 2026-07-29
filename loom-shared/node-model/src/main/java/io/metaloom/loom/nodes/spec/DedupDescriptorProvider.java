package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

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
				.setInputPorts(List.of(
					one("hash", HASH_ANY)
						.describedAs("Hash", "Any content hash. Two files whose hashes match are treated as the same file")))
				.setOutputPorts(List.of())
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
				.setInputPorts(List.of(
					one("fingerprint", HASH_FINGERPRINT)
						.describedAs("Fingerprint", "A perceptual fingerprint; files within the similarity threshold count as near-duplicates")))
				.setOutputPorts(List.of())
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
