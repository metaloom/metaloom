package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.one;
import static io.metaloom.loom.nodes.spec.PortSpec.optionalOne;

import java.util.List;

/**
 * Provides the node descriptor for the fingerprint analysis node.
 */
public class FingerprintDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("fingerprint")
				.setName("Fingerprint")
				.setDescription("Compute a perceptual fingerprint of the media for deduplication or similarity search.")
				.setIcon("grain")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("media", MEDIA_VIDEO)
						.describedAs("Video", "The video whose frames are sampled into a perceptual hash"),
					// Declared so the node can stop hard-coding an upstream node id for it. Leave it
					// unwired and a half-written file is fingerprinted anyway, which is the old behaviour.
					optionalOne("is_complete", SCALAR_BOOLEAN)
						.describedAs("Is Complete", "Whether the file is whole; an incomplete one is skipped unless processIncomplete is set")))
				.setOutputPorts(List.of(
					one("fingerprint", HASH_FINGERPRINT)
						.describedAs("Fingerprint", "Perceptual fingerprint that survives re-encoding, so near-duplicates still compare equal")))
				.setParameters(List.of(commonEnabled(), commonProcessIncomplete(), commonRetryFailed()))
				.setDefaultConcurrency(2)
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
