package io.metaloom.cortex.node.source.cloud;

import java.util.List;

import io.metaloom.cortex.cloud.CloudProviderId;

/**
 * Configured defaults for the {@code onedrive-source} node.
 *
 * <p>Identical to the Google options except that there is nothing to export: every OneDrive item
 * has real bytes, so {@code exportNativeDocs} does not exist here. Setting it is reported as an
 * error rather than ignored - a silent no-op in a config file is worse than a startup message.</p>
 */
public class OneDriveSourceNodeOptions extends CloudSourceNodeOptions<OneDriveSourceNodeOptions> {

	public static final String KEY = "onedrive-source";

	/**
	 * Present only so that a definition carrying the Google-only option is rejected with a useful
	 * message instead of being silently dropped by the JSON deserializer.
	 */
	private Boolean exportNativeDocs;

	@Override
	protected OneDriveSourceNodeOptions self() {
		return this;
	}

	@Override
	public CloudProviderId provider() {
		return CloudProviderId.ONEDRIVE;
	}

	@Override
	protected void validateProviderSpecific(List<String> errors) {
		if (exportNativeDocs != null) {
			errors.add("exportNativeDocs is a Google Drive option and does nothing on onedrive-source; "
				+ "every OneDrive item has downloadable bytes");
		}
	}

	public Boolean getExportNativeDocs() {
		return exportNativeDocs;
	}

	public OneDriveSourceNodeOptions setExportNativeDocs(Boolean exportNativeDocs) {
		this.exportNativeDocs = exportNativeDocs;
		return this;
	}
}
