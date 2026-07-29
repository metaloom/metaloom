package io.metaloom.cortex.pipeline.core.node.filter;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.test.AbstractNodeChainTest;
import io.metaloom.cortex.pipeline.test.CapturingNode;

/**
 * Shared fixture for the concrete filter node tests. Extends the standard chain
 * harness with the one thing it does not offer: a Y-branched pipeline, which is the
 * only way to observe that a filter actually routes items rather than merely emitting
 * a verdict.
 *
 * <p>Filter tests come in two layers:</p>
 * <ul>
 *   <li>{@link #evaluate(AbstractFilterNode, LoomMedia, NodeInputs)} — a direct
 *       {@code process()} call for the decision-table cases. Cheap, and it can fill a
 *       port with a value no real node would produce.</li>
 *   <li>{@link #route(LoomMedia, AbstractFilterNode, PipelineNode...)} — a real run
 *       through {@link #PASS_NODE} / {@link #REJECT_NODE}, which is what proves the
 *       verdict is wired to the branch.</li>
 * </ul>
 */
abstract class AbstractFilterNodeTest extends AbstractNodeChainTest {

	/** Id of the node connected to the filter's {@link FilterBranch#PASS} branch. */
	protected static final String PASS_NODE = "pass-branch";

	/** Id of the node connected to the filter's {@link FilterBranch#REJECT} branch. */
	protected static final String REJECT_NODE = "reject-branch";

	/**
	 * Invoke the filter directly, bypassing the harness.
	 *
	 * @param filter the filter under test
	 * @param media  the media item to evaluate
	 * @param inputs what the filter's input ports carry
	 */
	protected NodeResult evaluate(AbstractFilterNode filter, LoomMedia media, NodeInputs inputs) {
		return filter.process(media, inputs);
	}

	/**
	 * Invoke the filter directly with nothing wired into any of its ports.
	 */
	protected NodeResult evaluate(AbstractFilterNode filter, LoomMedia media) {
		return filter.process(media, NodeInputs.empty());
	}

	/**
	 * Fill a single {@code ONE} input port — the common shape for a filter that reads
	 * one upstream value.
	 */
	protected <T> NodeInputs input(InputPort<T> port, T value) {
		return NodeInputs.builder().input(port, value).build();
	}

	/**
	 * Whether the filter passed. Reads the verdict by port rather than by map key, so
	 * a renamed verdict port would be a compile error rather than a silent false.
	 */
	protected boolean passed(NodeResult result) {
		return Boolean.TRUE.equals(result.get(FILTER_VERDICT));
	}

	/**
	 * Execute the filter in a real pipeline with both branches populated:
	 *
	 * <pre>
	 *   asset-source -&gt; [upstream...] -&gt; filter --(PASS)--&gt;   pass-branch
	 *                                          --(REJECT)--&gt; reject-branch
	 * </pre>
	 *
	 * Both branch nodes capture the filter's verdict, so a test can assert on the
	 * routing decision in one run.
	 *
	 * @param media    the media item to process
	 * @param filter   the filter under test
	 * @param upstream nodes to chain between the source and the filter, in order
	 */
	protected PipelineResult route(LoomMedia media, AbstractFilterNode filter, PipelineNode... upstream) {
		PipelineNode[] chain = new PipelineNode[upstream.length + 1];
		System.arraycopy(upstream, 0, chain, 0, upstream.length);
		chain[upstream.length] = filter;

		// Run everything up to and including the filter, then apply the branch
		// decision the Loom engine would make: only the node wired to the matching
		// branch runs; the other is skipped.
		return executeWithBranches(media, filter.id(), chain,
			new CapturingNode(PASS_NODE, FILTER_VERDICT),
			new CapturingNode(REJECT_NODE, FILTER_VERDICT));
	}
}
