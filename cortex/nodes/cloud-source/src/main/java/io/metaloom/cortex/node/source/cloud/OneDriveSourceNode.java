package io.metaloom.cortex.node.source.cloud;

import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.cloud.CloudMediaMaterializer;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeMode;

/**
 * The {@code onedrive-source} contract.
 *
 * <p>
 * A thin subclass that adds no behaviour — see {@link GDriveSourceNode} for why the two exist at all.
 * </p>
 *
 * <p>
 * Almost every parameter here is an override rather than a fresh declaration. The fields live on the
 * shared {@code CloudSourceNodeOptions}, whose {@code @ParamDoc} prose speaks for Google Drive, and
 * seven of the nine say something meaningfully different for OneDrive: a "shared drive" is a "document
 * library", trashed files are "deleted" ones, and the change feed is a different service with
 * different caveats. {@code exportNativeDocs} is refused outright — the field exists on the OneDrive
 * options only so a definition carrying the Google-only option fails with a useful message instead of
 * being silently dropped.
 * </p>
 *
 * <p>
 * Note the deliberate absence of {@code order} on any override. The shared options class declares none,
 * so declaration order is the contract - and it already matches. Ordering one parameter would have
 * meant ordering all nine, because an un-ordered parameter sorts behind every ordered one.
 * </p>
 */
@NodeSpec(nodeId = "onedrive-source", name = "OneDrive Source", icon = "cloud_queue", category = NodeCategory.SOURCE,
	description = "Reads media files from OneDrive or a SharePoint document library as pipeline "
		+ "input. Only new, changed and moved files are picked up on a re-run.",
	defaultMode = NodeMode.SEQUENTIAL,
	optionsClass = OneDriveSourceNodeOptions.class,
	parameters = {
		@ParamOverride(key = "processIncomplete", hidden = true),
		@ParamOverride(key = "retryFailed", hidden = true),
		@ParamOverride(key = "exportNativeDocs", hidden = true),
		@ParamOverride(key = "driveId", label = "Drive ID",
			description = "The OneDrive or SharePoint library to read from. Required unless a default "
				+ "drive is configured on the worker. Credentials are configured on the worker, not here"),
		// Seven of the nine differ, not six: a Drive folder id comes from its URL, a OneDrive one does not.
		@ParamOverride(key = "folderId", label = "Folder ID",
			description = "Folder to scan. Empty scans the whole drive"),
		@ParamOverride(key = "mimeTypes", label = "MIME types",
			description = "Comma-separated MIME type prefixes to accept, e.g. video/,image/. OneDrive "
				+ "reports a real type, so this also catches files with a missing or misleading extension"),
		// An override replaces the whole parameter, so the choice list has to be restated - it is not
		// inherited from the @ParamDoc being overridden.
		@ParamOverride(key = "emitStates", label = "Emit states",
			description = "Which changes flow downstream. Renames and moves are detected, because a "
				+ "OneDrive file keeps its identity when it is moved",
			values = { "NEW", "MODIFIED", "MOVED", "PRESENT", "DELETED" }),
		@ParamOverride(key = "useDelta", label = "Use the change feed",
			description = "Ask OneDrive what changed instead of listing the folders. Much faster on "
				+ "large drives. A full scan still runs periodically, so nothing is missed"),
		@ParamOverride(key = "includeTrashed", label = "Include deleted files",
			description = "Keep files that are in the recycle bin") })
public class OneDriveSourceNode extends CloudSourceNode {

	OneDriveSourceNode(String id, CloudDifferentialScanner scanner, CloudMediaMaterializer materializer,
		CloudSelection selection) {
		super(id, scanner, materializer, selection);
	}
}
