package io.metaloom.cortex.pipeline.core.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.PipelineRunContext;
import io.metaloom.cortex.pipeline.api.node.MediaSourceNode;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.core.node.AssetSourceNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Covers {@code PipelineExecutor.execute(Pipeline, PipelineRunContext)} — the entry
 * point that takes its media selection from the pipeline's own source node instead
 * of from the caller.
 */
class SourceDrivenExecutionTest {

	@Test
	void testExecutionDrawsMediaFromTheSourceNode() {
		List<LoomMedia> items = List.of(new StubLoomMedia("/tmp/a.mp4"), new StubLoomMedia("/tmp/b.mp4"),
			new StubLoomMedia("/tmp/c.mp4"));
		RecordingNode downstream = new RecordingNode("recorder");

		CollectionSourceNode source = new CollectionSourceNode("collection-source", items);
		source.connectTo(downstream);
		Pipeline pipeline = DefaultPipeline.builder("source-driven").source(source).build();

		List<PipelineResult> results = new ReactivePipelineExecutor(2)
			.execute(pipeline, PipelineRunContext.none())
			.toList()
			.blockingGet();

		assertEquals(3, results.size(), "every media item yielded by the source should be processed");
		assertEquals(3, downstream.seen.size(), "downstream node should see every item");
	}

	@Test
	void testAssetSourceNodeYieldsItsSingleAsset() {
		LoomMedia asset = new StubLoomMedia("/tmp/only.mp4");
		AssetSourceNode source = new AssetSourceNode(asset);
		Pipeline pipeline = DefaultPipeline.builder("single-asset").source(source).build();

		List<PipelineResult> results = new ReactivePipelineExecutor(1)
			.execute(pipeline, PipelineRunContext.none())
			.toList()
			.blockingGet();

		assertEquals(1, results.size());
		assertEquals(asset, results.get(0).getMedia());
	}

	@Test
	void testSourceThatCannotEnumerateMediaFailsWithAClearError() {
		// A plain marker source carries no selection, so the run cannot be
		// started without the caller supplying media.
		MarkerSourceNode source = new MarkerSourceNode("marker");
		Pipeline pipeline = DefaultPipeline.builder("marker-source").source(source).build();

		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> new ReactivePipelineExecutor(1)
				.execute(pipeline, PipelineRunContext.none())
				.toList()
				.blockingGet());

		assertInstanceOf(IllegalStateException.class, error);
	}

	@Test
	void testEmptySourceCompletesWithoutResults() {
		CollectionSourceNode source = new CollectionSourceNode("empty-source", List.of());
		Pipeline pipeline = DefaultPipeline.builder("empty").source(source).build();

		List<PipelineResult> results = new ReactivePipelineExecutor(1)
			.execute(pipeline, PipelineRunContext.none())
			.toList()
			.blockingGet();

		assertEquals(0, results.size());
	}

	/** Source node backed by a fixed collection. */
	private static class CollectionSourceNode extends AbstractPipelineNode implements MediaSourceNode {

		private final List<LoomMedia> items;

		CollectionSourceNode(String id, List<LoomMedia> items) {
			super(id, "Collection Source", NodeMode.SEQUENTIAL, true, 1);
			this.items = items;
			setSource(true);
		}

		@Override
		public Flowable<LoomMedia> stream() {
			return Flowable.fromIterable(items);
		}

		@Override
		public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			return NodeResult.success(id(), 0, Map.of("path", media.absolutePath()));
		}
	}

	/** Source node that only marks itself as a source and cannot enumerate media. */
	private static class MarkerSourceNode extends AbstractPipelineNode {

		MarkerSourceNode(String id) {
			super(id, "Marker Source", NodeMode.SEQUENTIAL, true, 1);
			setSource(true);
		}

		@Override
		public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			return NodeResult.success(id(), 0, Map.of());
		}
	}

	/** Downstream node that records the media it was handed. */
	private static class RecordingNode extends AbstractPipelineNode {

		private final List<LoomMedia> seen = new ArrayList<>();

		RecordingNode(String id) {
			super(id, "Recorder", NodeMode.PARALLEL, true, 4);
		}

		@Override
		public synchronized NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			seen.add(media);
			return NodeResult.success(id(), 0, Map.of());
		}
	}
}
