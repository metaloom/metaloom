package io.metaloom.cortex.pipeline.core.node;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;

/**
 * Source node that emits exactly one configured media asset per pipeline run.
 *
 * <p>The first invocation returns {@link NodeResult#success(String, long, Map)}
 * with the configured asset path. Subsequent invocations in the same run return
 * {@link NodeResult#skipped(String, String)}.</p>
 */
public class AssetSourceNode extends AbstractPipelineNode {

	private static final String OUTPUT_PATH = "path";
	private static final String OUTPUT_SOURCE = "source";

	private final LoomMedia asset;
	private final AtomicBoolean emitted = new AtomicBoolean(false);

	public AssetSourceNode(LoomMedia asset) {
		this("asset-source", "Asset Source", asset);
	}

	public AssetSourceNode(String id, String name, LoomMedia asset) {
		super(id, name, NodeMode.SEQUENTIAL, true, 1);
		if (asset == null) {
			throw new IllegalArgumentException("Asset must not be null");
		}
		this.asset = asset;
		setSource(true);
	}

	@Override
	public void initialize() {
		emitted.set(false);
	}

	@Override
	public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		if (!emitted.compareAndSet(false, true)) {
			return NodeResult.skipped(id(), "Asset was already emitted");
		}
		return NodeResult.success(id(), 0, Map.of(
			OUTPUT_PATH, asset.absolutePath(),
			OUTPUT_SOURCE, "asset"));
	}

	public LoomMedia asset() {
		return asset;
	}
}
