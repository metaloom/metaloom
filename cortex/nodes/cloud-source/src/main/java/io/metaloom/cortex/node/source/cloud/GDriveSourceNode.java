package io.metaloom.cortex.node.source.cloud;

import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.cloud.CloudMediaMaterializer;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeMode;

/**
 * The {@code gdrive-source} contract.
 *
 * <p>
 * A thin subclass that adds no behaviour: scanning is provider-agnostic and lives entirely in
 * {@link CloudSourceNode}. It exists because {@code gdrive-source} and {@code onedrive-source} are two
 * genuinely different <em>contracts</em> — different name, icon and description, different prose on six
 * of their nine shared parameters, and one option Google Drive has that OneDrive refuses — and a class
 * can carry only one {@code @NodeSpec}.
 * </p>
 *
 * <p>
 * The alternative was making {@code @NodeSpec} repeatable, which would cost {@code harvest(Class)} its
 * single answer. Two near-empty classes are the cheaper trade.
 * </p>
 */
@NodeSpec(nodeId = "gdrive-source", name = "Google Drive Source", icon = "add_to_drive", category = NodeCategory.SOURCE,
	description = "Reads media files from Google Drive - My Drive or a shared drive - as pipeline "
		+ "input. Only new, changed and moved files are picked up on a re-run.",
	defaultMode = NodeMode.SEQUENTIAL,
	// A source node implements MediaSourceNode rather than extending AbstractMediaNode, so there is no
	// generic superclass for the harvester to read the options type from.
	optionsClass = GDriveSourceNodeOptions.class,
	// No source descriptor has ever advertised these two; they are media-processing knobs.
	parameters = {
		@ParamOverride(key = "processIncomplete", hidden = true),
		@ParamOverride(key = "retryFailed", hidden = true) })
public class GDriveSourceNode extends CloudSourceNode {

	GDriveSourceNode(String id, CloudDifferentialScanner scanner, CloudMediaMaterializer materializer,
		CloudSelection selection) {
		super(id, scanner, materializer, selection);
	}
}
