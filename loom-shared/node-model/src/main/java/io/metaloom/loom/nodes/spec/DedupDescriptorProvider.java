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
				.setNodeId("hash-dedup")
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

			// This node discovers candidate groups; it moves nothing, so it has no dupFolder. It used to
			// advertise one, which did nothing - the node is injected with FingerprintDedupDiscoverOptions,
			// which has no such field - while the five options it actually reads were advertised nowhere,
			// leaving the similarity threshold of a similarity node unreachable from the editor.
			new NodeDescriptor()
				.setNodeId("fingerprint-dedup")
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
					new NodeParameter().setKey("algorithm").setType(STRING).setDefaultValue("metaloom-multisector-v1")
						.setLabel("Algorithm")
						.setDescription("Fingerprint algorithm whose vectors are compared. Must match the algorithm the "
							+ "upstream fingerprint node produced, or nothing will be found similar"),
					new NodeParameter().setKey("scoreThreshold").setType(NUMBER).setDefaultValue(0.10f)
						.setLabel("Score Threshold")
						.setDescription("Maximum distance at which two fingerprints count as near-duplicates. Lower is "
							+ "stricter; 0 matches only identical fingerprints").setMin(0.0),
					new NodeParameter().setKey("topK").setType(INTEGER).setDefaultValue(10)
						.setLabel("Neighbours")
						.setDescription("How many nearest neighbours to examine per item before deciding a group").setMin(1),
					new NodeParameter().setKey("allowPartial").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Allow Partial Groups")
						.setDescription("Form a group even when no member is known to be complete. Off by default: never "
							+ "propose discarding a file where completeness is unknown"),
					new NodeParameter().setKey("abortOnLargerDup").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Abort On Larger Duplicate")
						.setDescription("Abandon a candidate group if any duplicate is larger than the file being kept, so "
							+ "the more complete file is never the one discarded")))
				.setDefaultConcurrency(1)
				.setDefaultMode(SEQUENTIAL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setNodeId("fingerprint-dedup-apply")
				.setName("Fingerprint Deduplication (Apply)")
				.setDescription("Move confirmed near-duplicate files. Acts only on dedup groups a reviewer has confirmed in Loom.")
				.setIcon("content_copy")
				.setCategory(OUTPUT)
				.setInputPorts(List.of(
					one("hash", HASH_ANY)
						.describedAs("Hash", "Any content hash; identifies the asset whose confirmed dedup groups are applied")))
				.setOutputPorts(List.of())
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("dupFolder").setType(STRING).setDefaultValue("duplicates")
						.setLabel("Duplicates Folder").setDescription("Target folder for confirmed duplicate files")))
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
