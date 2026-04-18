package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Provides node descriptors for hash computation nodes (MD5, SHA-256, SHA-512, Chunk Hash).
 */
public class HashDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("md5")
				.setName("MD5 Hash")
				.setDescription("Compute the MD5 hash of the media file.")
				.setIcon("fingerprint")
				.setCategory(ANALYSIS)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(new NodeOutput("md5", DATA_HASH)))
				.setParameters(List.of(commonEnabled(), commonProcessIncomplete(), commonRetryFailed()))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("sha256")
				.setName("SHA-256 Hash")
				.setDescription("Compute the SHA-256 hash of the media file.")
				.setIcon("fingerprint")
				.setCategory(ANALYSIS)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(new NodeOutput("sha256", DATA_HASH)))
				.setParameters(List.of(commonEnabled(), commonProcessIncomplete(), commonRetryFailed()))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("sha512")
				.setName("SHA-512 Hash")
				.setDescription("Compute the SHA-512 hash of the media file.")
				.setIcon("fingerprint")
				.setCategory(ANALYSIS)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(new NodeOutput("sha512", DATA_HASH)))
				.setParameters(List.of(commonEnabled(), commonProcessIncomplete(), commonRetryFailed()))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("chunk-hash")
				.setName("Chunk Hash")
				.setDescription("Compute a hash over fixed-size chunks of the media file.")
				.setIcon("fingerprint")
				.setCategory(ANALYSIS)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(new NodeOutput("chunk_hash", DATA_HASH)))
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
