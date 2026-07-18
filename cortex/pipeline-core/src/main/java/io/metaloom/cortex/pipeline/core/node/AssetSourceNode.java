package io.metaloom.cortex.pipeline.core.node;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.node.MediaSourceNode;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Source node that emits exactly one configured media asset per pipeline run.
 *
 * <p>The first invocation returns {@link NodeResult#success(String, long, Map)}
 * with the configured asset path. Subsequent invocations in the same run return
 * {@link NodeResult#skipped(String, String)}.</p>
 *
 * <p>As a {@link MediaSourceNode} its {@link #stream()} is the single configured
 * asset, so a pipeline built around it can be run via
 * {@link io.metaloom.cortex.pipeline.api.PipelineExecutor#execute(io.metaloom.cortex.pipeline.api.Pipeline, io.metaloom.cortex.pipeline.api.PipelineRunContext)}
 * without the caller supplying a media stream.</p>
 */
public class AssetSourceNode extends AbstractPipelineNode implements MediaSourceNode {

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

	@Override
	public Flowable<LoomMedia> stream() {
		return Flowable.just(asset);
	}

	public LoomMedia asset() {
		return asset;
	}
}
