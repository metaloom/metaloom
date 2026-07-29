package io.metaloom.cortex.pipeline.core.node;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.node.MediaSourceNode;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

import java.util.List;
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
 * the Loom-side pipeline engine, which dispatches one node task at a time
 * without the caller supplying a media stream.</p>
 */
public class AssetSourceNode extends AbstractPipelineNode implements MediaSourceNode {

	/** What every source emits: a reference the downstream nodes resolve to a media handle. */
	public static final OutputPort<String> OUT_MEDIA = OutputPort.one("media", ContentTypeRegistry.MEDIA_ANY, String.class);

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
	public NodeResult process(LoomMedia media, NodeInputs inputs) {
		if (!emitted.compareAndSet(false, true)) {
			return NodeResult.skipped(id(), "Asset was already emitted");
		}
		return NodeResult.success(id(), 0,
			Map.of(OUT_MEDIA.id(), new PortOutput(OUT_MEDIA, List.of(asset.absolutePath()))));
	}

	@Override
	public Flowable<LoomMedia> stream() {
		return Flowable.just(asset);
	}

	public LoomMedia asset() {
		return asset;
	}
}
