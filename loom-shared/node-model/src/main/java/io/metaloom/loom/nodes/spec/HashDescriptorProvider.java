package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The file whose bytes are digested")))
				.setOutputPorts(List.of(
					one("hash", HASH_MD5)
						.describedAs("MD5", "Lowercase hex MD5 digest over the whole file")))
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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The file whose bytes are digested")))
				.setOutputPorts(List.of(
					one("hash", HASH_SHA256)
						.describedAs("SHA-256", "Lowercase hex SHA-256 digest over the whole file")))
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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The file whose bytes are digested")))
				.setOutputPorts(List.of(
					one("hash", HASH_SHA512)
						.describedAs("SHA-512", "Lowercase hex SHA-512 digest over the whole file")))
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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The file whose bytes are digested chunk by chunk")))
				.setOutputPorts(List.of(
					one("hash", HASH_CHUNK)
						.describedAs("Chunk Hash", "Digest over fixed-size chunks, so a file that was only partly rewritten still matches in part")))
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
