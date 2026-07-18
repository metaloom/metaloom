package io.metaloom.cortex.pipeline.core.node.filter;

import java.util.Map;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.node.AssetSourceNode;
import io.metaloom.cortex.pipeline.test.AbstractPipelineNodeTest;

/**
 * Shared fixture for the concrete filter node tests. Extends the standard
 * {@link AbstractPipelineNodeTest} with the one thing it does not offer: a
 * Y-branched pipeline, which is the only way to observe that a filter actually
 * routes items rather than merely emitting {@code filter_passed}.
 *
 * <p>Filter tests come in two layers:</p>
 * <ul>
 *   <li>{@link #evaluate(AbstractFilterNode, LoomMedia, Map)} — a direct
 *       {@code process()} call for the decision-table cases. Cheap, and it can
 *       inject upstream results that no real node would produce.</li>
 *   <li>{@link #route(LoomMedia, AbstractFilterNode, PipelineNode...)} — a real
 *       executor run through {@link #PASS_NODE} / {@link #REJECT_NODE}, which is
 *       what proves the {@code filter_passed} output is wired to the branch.</li>
 * </ul>
 */
abstract class AbstractFilterNodeTest extends AbstractPipelineNodeTest {

	/** Id of the node connected to the filter's {@link FilterBranch#PASS} branch. */
	protected static final String PASS_NODE = "pass-branch";

	/** Id of the node connected to the filter's {@link FilterBranch#REJECT} branch. */
	protected static final String REJECT_NODE = "reject-branch";

	/**
	 * Invoke the filter directly, bypassing the executor.
	 *
	 * @param filter   the filter under test
	 * @param media    the media item to evaluate
	 * @param upstream upstream results keyed by node id, as the executor would pass them
	 */
	protected NodeResult evaluate(AbstractFilterNode filter, LoomMedia media, Map<String, NodeResult> upstream) {
		return filter.process(media, upstream);
	}

	/**
	 * Invoke the filter directly with no upstream results at all.
	 */
	protected NodeResult evaluate(AbstractFilterNode filter, LoomMedia media) {
		return filter.process(media, Map.of());
	}

	/**
	 * Build an upstream result map holding a single node's outputs — the common
	 * shape for filters that read one quality/hash node.
	 */
	protected Map<String, NodeResult> upstream(String nodeId, Map<String, Object> outputs) {
		return Map.of(nodeId, NodeResult.success(nodeId, 0, outputs));
	}

	/**
	 * Execute the filter in a real pipeline with both branches populated:
	 *
	 * <pre>
	 *   asset-source -&gt; [upstream...] -&gt; filter --(PASS)--&gt;   pass-branch
	 *                                          --(REJECT)--&gt; reject-branch
	 * </pre>
	 *
	 * Both branch nodes are {@code CapturingNode}s bound to the filter's
	 * {@code filter_reason} output, so a test can assert on the routing decision
	 * and on the reason string in one run.
	 *
	 * @param media    the media item to process
	 * @param filter   the filter under test
	 * @param upstream nodes to chain between the source and the filter, in order
	 */
	protected PipelineResult route(LoomMedia media, AbstractFilterNode filter, PipelineNode... upstream) {
		AssetSourceNode source = new AssetSourceNode(media);
		PipelineNode prev = source;
		for (PipelineNode node : upstream) {
			prev.connectTo(node);
			prev = node;
		}
		prev.connectTo(filter);

		filter.connectTo(passNode(filter), FilterBranch.PASS);
		filter.connectTo(rejectNode(filter), FilterBranch.REJECT);

		Pipeline pipeline = DefaultPipeline.builder("filter-routing-test")
				.source(source)
				.build();
		return executor.execute(pipeline, media);
	}

	private PipelineNode passNode(AbstractFilterNode filter) {
		return new io.metaloom.cortex.pipeline.test.CapturingNode(PASS_NODE, filter.id(), "filter_reason");
	}

	private PipelineNode rejectNode(AbstractFilterNode filter) {
		return new io.metaloom.cortex.pipeline.test.CapturingNode(REJECT_NODE, filter.id(), "filter_reason");
	}
}
