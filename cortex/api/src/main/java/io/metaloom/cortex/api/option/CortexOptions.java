package io.metaloom.cortex.api.option;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import io.metaloom.cortex.api.option.node.CortexNodeOptions;

public class CortexOptions {

	private Map<String, CortexNodeOptions> nodes = new HashMap<>();
	private LoomClientOptions loom = new LoomClientOptions();
	private boolean dryrun;

	private Path metaPath;

	public CortexOptions() {
	}

	public Map<String, CortexNodeOptions> getNodes() {
		return nodes;
	}

	public CortexOptions setNodes(Map<String, CortexNodeOptions> nodes) {
		this.nodes = nodes;
		return this;
	}

	public LoomClientOptions getLoom() {
		return loom;
	}

	public CortexOptions setLoom(LoomClientOptions loom) {
		this.loom = loom;
		return this;
	}

	public boolean isDryrun() {
		return dryrun;
	}

	public CortexOptions setDryrun(boolean dryrun) {
		this.dryrun = dryrun;
		return this;
	}

	/**
	 * Basepath for the metadata storage files.
	 * 
	 * @return
	 */
	public Path getMetaPath() {
		return metaPath;
	}

	public CortexOptions setMetaPath(Path metaPath) {
		this.metaPath = metaPath;
		return this;
	}

}
