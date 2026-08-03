package io.metaloom.cortex.node.source.cloud;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.cloud.CloudProviderId;

/**
 * Configured defaults for the {@code gdrive-source} node.
 *
 * <p>The {@code KEY} matches the node kind, the descriptor kind and the {@code name()} the node
 * reports - all four strings have to be the same or the node is unreachable from one direction or
 * another.</p>
 */
public class GDriveSourceNodeOptions extends CloudSourceNodeOptions<GDriveSourceNodeOptions> {

	public static final String KEY = "gdrive-source";

	/**
	 * Whether Google Docs, Sheets and Slides are exported rather than skipped.
	 *
	 * <p>Off by default. A native document has no bytes and no reported size; reading one means
	 * exporting it to PDF or CSV, which Google caps at 10 MB of output and which is rarely what a
	 * media pipeline wants. With this off such documents are filtered out during the scan and never
	 * enter the index.</p>
	 */
	@ParamDoc(label = "Export Google Docs", description = "Convert Google Docs, Sheets and Slides so they can be processed. They have "
		+ "no file to download otherwise. Conversion is limited to 10 MB per document")
	private boolean exportNativeDocs;

	@Override
	protected GDriveSourceNodeOptions self() {
		return this;
	}

	@Override
	public CloudProviderId provider() {
		return CloudProviderId.GDRIVE;
	}

	public boolean isExportNativeDocs() {
		return exportNativeDocs;
	}

	public GDriveSourceNodeOptions setExportNativeDocs(boolean exportNativeDocs) {
		this.exportNativeDocs = exportNativeDocs;
		return this;
	}
}
