package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides node descriptors for pipeline source nodes (Filesystem Source, S3 Source, Google Drive
 * Source, OneDrive Source, Loom Fetch).
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

	/**
	 * Cloud drives keep a stable id per file and report its parent folder, so a rename or a move
	 * <em>is</em> distinguishable from a delete plus an add - unlike S3, which has no inode and
	 * therefore omits {@code MOVED}. It is offered here because it is genuinely produced.
	 */
	private static final List<String> CLOUD_EMIT_STATES = List.of("NEW", "MODIFIED", "MOVED", "PRESENT", "DELETED");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setNodeId("filesystem-source")
				.setName("Filesystem Source")
				.setDescription("Reads media files from the filesystem as pipeline input.")
				.setIcon("folder_open")
				.setCategory(SOURCE)
				.setInputPorts(List.of())
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "Every file the differential scan emitted. The concrete kind is unknown until the file is opened, "
							+ "so this is the family wildcard")))
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
				.setNodeId("s3-source")
				.setName("S3 Source")
				.setDescription("Reads media objects from S3-compatible object storage (AWS S3, MinIO, Ceph) "
					+ "as pipeline input. Only new and changed objects are picked up on a re-run.")
				.setIcon("cloud")
				.setCategory(SOURCE)
				.setInputPorts(List.of())
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "Every object the bucket listing emitted. The concrete kind is only known once the object is fetched")))
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
				.setNodeId("gdrive-source")
				.setName("Google Drive Source")
				.setDescription("Reads media files from Google Drive - My Drive or a shared drive - as pipeline "
					+ "input. Only new, changed and moved files are picked up on a re-run.")
				.setIcon("add_to_drive")
				.setCategory(SOURCE)
				.setInputPorts(List.of())
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "Every file the scan emitted. The concrete kind is only known once the file is fetched")))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("driveId").setType(STRING).setLabel("Shared drive ID")
						.setDescription("Shared drive to read from. Leave empty for the connected account's own "
							+ "My Drive. Credentials are configured on the worker, not here"),
					new NodeParameter().setKey("folderId").setType(STRING).setLabel("Folder ID")
						.setDescription("Folder to scan, taken from its Drive URL. Empty scans the whole drive"),
					new NodeParameter().setKey("recursive").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Include sub-folders")
						.setDescription("Descend into folders below the selected one"),
					new NodeParameter().setKey("maxDepth").setType(INTEGER).setDefaultValue(0)
						.setLabel("Maximum depth")
						.setDescription("How many folder levels to descend. 0 means no limit"),
					new NodeParameter().setKey("suffixes").setType(STRING).setLabel("File suffixes")
						.setDescription("Comma-separated suffixes to accept, e.g. mp4,mkv,jpg. Empty accepts everything"),
					new NodeParameter().setKey("mimeTypes").setType(STRING).setLabel("MIME types")
						.setDescription("Comma-separated MIME type prefixes to accept, e.g. video/,image/. Drive "
							+ "reports a real type, so this also catches files with a missing or misleading extension"),
					new NodeParameter().setKey("emitStates").setType(ENUM_SET).setValues(CLOUD_EMIT_STATES)
						.setDefaultValue(List.of("NEW", "MODIFIED", "MOVED")).setLabel("Emit states")
						.setDescription("Which changes flow downstream. Renames and moves are detected, because a "
							+ "Drive file keeps its identity when it is moved"),
					new NodeParameter().setKey("useDelta").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Use the change feed")
						.setDescription("Ask Drive what changed instead of listing the folders. Much faster on large "
							+ "drives. A full scan still runs periodically, so nothing is missed"),
					new NodeParameter().setKey("includeTrashed").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Include trashed files")
						.setDescription("Keep files that are in the Drive trash"),
					new NodeParameter().setKey("exportNativeDocs").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Export Google Docs")
						.setDescription("Convert Google Docs, Sheets and Slides so they can be processed. They have "
							+ "no file to download otherwise. Conversion is limited to 10 MB per document")))
				.setDefaultConcurrency(1)
				.setDefaultMode(SEQUENTIAL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setNodeId("onedrive-source")
				.setName("OneDrive Source")
				.setDescription("Reads media files from OneDrive or a SharePoint document library as pipeline "
					+ "input. Only new, changed and moved files are picked up on a re-run.")
				.setIcon("cloud_queue")
				.setCategory(SOURCE)
				.setInputPorts(List.of())
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "Every file the scan emitted. The concrete kind is only known once the file is fetched")))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("driveId").setType(STRING).setLabel("Drive ID")
						.setDescription("The OneDrive or SharePoint library to read from. Required unless a default "
							+ "drive is configured on the worker. Credentials are configured on the worker, not here"),
					new NodeParameter().setKey("folderId").setType(STRING).setLabel("Folder ID")
						.setDescription("Folder to scan. Empty scans the whole drive"),
					new NodeParameter().setKey("recursive").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Include sub-folders")
						.setDescription("Descend into folders below the selected one"),
					new NodeParameter().setKey("maxDepth").setType(INTEGER).setDefaultValue(0)
						.setLabel("Maximum depth")
						.setDescription("How many folder levels to descend. 0 means no limit"),
					new NodeParameter().setKey("suffixes").setType(STRING).setLabel("File suffixes")
						.setDescription("Comma-separated suffixes to accept, e.g. mp4,mkv,jpg. Empty accepts everything"),
					new NodeParameter().setKey("mimeTypes").setType(STRING).setLabel("MIME types")
						.setDescription("Comma-separated MIME type prefixes to accept, e.g. video/,image/. OneDrive "
							+ "reports a real type, so this also catches files with a missing or misleading extension"),
					new NodeParameter().setKey("emitStates").setType(ENUM_SET).setValues(CLOUD_EMIT_STATES)
						.setDefaultValue(List.of("NEW", "MODIFIED", "MOVED")).setLabel("Emit states")
						.setDescription("Which changes flow downstream. Renames and moves are detected, because a "
							+ "OneDrive file keeps its identity when it is moved"),
					new NodeParameter().setKey("useDelta").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Use the change feed")
						.setDescription("Ask OneDrive what changed instead of listing the folders. Much faster on "
							+ "large drives. A full scan still runs periodically, so nothing is missed"),
					new NodeParameter().setKey("includeTrashed").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Include deleted files")
						.setDescription("Keep files that are in the recycle bin")))
				.setDefaultConcurrency(1)
				.setDefaultMode(SEQUENTIAL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setNodeId("loom-fetch")
				.setName("Loom Fetch")
				.setDescription("Fetches media references from the Loom backend.")
				.setIcon("cloud_download")
				.setCategory(SOURCE)
				.setInputPorts(List.of())
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "Every media reference the Loom backend handed out for processing")))
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
