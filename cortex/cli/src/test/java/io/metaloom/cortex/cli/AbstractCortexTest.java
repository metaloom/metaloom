package io.metaloom.cortex.cli;

import java.nio.file.Paths;
import java.util.Map.Entry;

import io.metaloom.cortex.node.thumbnail.ThumbnailNodeOptions;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;

public abstract class AbstractCortexTest {

	protected CortexOptions createOptions() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Paths.get("target", "meta"));
		options.getLoom().setHostname(null);

		// ActionOptions options = new ActionOptions();
		// options.getProcessorSettings().setPort(getPort());
		// options.getProcessorSettings().setHostname(getHostname());
		ThumbnailNodeOptions thumbnailActionOptions = new ThumbnailNodeOptions();
		options.getNodes().put(ThumbnailNodeOptions.KEY, thumbnailActionOptions);
		for (Entry<String, CortexNodeOptions> entry : options.getNodes().entrySet()) {
			System.out.println(entry.getKey() + " " + entry.getValue());
		}
		return options;
	}
}
