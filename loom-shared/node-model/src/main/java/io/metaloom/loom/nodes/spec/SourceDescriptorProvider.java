package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Provides node descriptors for pipeline source nodes (Filesystem Source, S3 Source, Loom Fetch).
 */
public class SourceDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	/**
	 * Diff states a differential source can emit.
	 *
	 * <p>{@code MOVED} is offered by {@code filesystem-source}, which tracks inodes and can tell a
	 * rename from a delete plus an add. {@code s3-source} cannot - object storage has no inode -
	 * so its own list deliberately omits it rather than offering a choice that does nothing.</p>
	 */
	private static final List<String> FS_EMIT_STATES = List.of("NEW", "MODIFIED", "MOVED", "PRESENT", "DELETED");
	private static final List<String> S3_EMIT_STATES = List.of("NEW", "MODIFIED", "PRESENT", "DELETED");

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
						.setDescription("Root directory to scan for media files. Scanned differentially: "
							+ "a local index remembers what was seen, so a re-run only picks up changes"),
					new NodeParameter().setKey("pathGlobs").setType(JSON).setLabel("Path globs")
						.setDescription("Glob patterns to expand instead of scanning a root, e.g. "
							+ "[\"/media/**/*.mp4\"]. Takes precedence over Path, and always re-enumerates "
							+ "every match because differential scanning needs a single root")
						.setRows(3),
					new NodeParameter().setKey("emitStates").setType(ENUM_SET).setValues(FS_EMIT_STATES)
						.setDefaultValue(List.of("NEW", "MODIFIED", "MOVED")).setLabel("Emit states")
						.setDescription("Which changes flow downstream. Only applies when scanning a root"),
					new NodeParameter().setKey("indexPath").setType(STRING).setLabel("Index directory")
						.setDescription("Where the per-root scan index is kept. Defaults to a directory "
							+ "under the worker's metadata path")))
				.setDefaultConcurrency(1)
				.setDefaultMode(SEQUENTIAL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("s3-source")
				.setName("S3 Source")
				.setDescription("Reads media objects from S3-compatible object storage (AWS S3, MinIO, Ceph) "
					+ "as pipeline input. Only new and changed objects are picked up on a re-run.")
				.setIcon("cloud")
				.setCategory(SOURCE)
				.setInputs(List.of())
				.setOutputs(List.of(new NodeOutput("media", MEDIA_ANY)))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("bucket").setType(STRING).setLabel("Bucket")
						.setDescription("Bucket to read from. Connection settings and credentials are "
							+ "configured on the worker, not here"),
					new NodeParameter().setKey("prefix").setType(STRING).setLabel("Prefix")
						.setDescription("Key prefix to limit the scan, e.g. 2026/07/. Empty scans the whole bucket"),
					new NodeParameter().setKey("suffixes").setType(STRING).setLabel("File suffixes")
						.setDescription("Comma-separated suffixes to accept, e.g. mp4,mkv,jpg. Empty accepts everything"),
					new NodeParameter().setKey("emitStates").setType(ENUM_SET).setValues(S3_EMIT_STATES)
						.setDefaultValue(List.of("NEW", "MODIFIED")).setLabel("Emit states")
						.setDescription("Which changes flow downstream"),
					new NodeParameter().setKey("useEvents").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Use bucket notifications")
						.setDescription("Process only the objects the bucket reported as changed, instead of "
							+ "listing it. Requires notifications to be enabled on the worker. A full listing "
							+ "still runs periodically, so nothing is missed if a notification is lost"),
					new NodeParameter().setKey("startAfter").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Resume from last key")
						.setDescription("Continue listing after the highest key seen so far. Only correct for "
							+ "buckets whose keys are added in ascending order and never edited afterwards")))
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
