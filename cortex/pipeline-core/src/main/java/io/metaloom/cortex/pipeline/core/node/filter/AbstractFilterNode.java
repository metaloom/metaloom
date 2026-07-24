package io.metaloom.cortex.pipeline.core.node.filter;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Base class for filter nodes that produce a Y-branch in the pipeline.
 *
 * <p>A filter node evaluates a condition on the media item (and optionally upstream outputs)
 * and emits a {@value PipelineNode#FILTER_PASSED} boolean output. Downstream nodes can
 * use {@link PipelineNode#conditionalDependencies()} to bind to either the PASS or REJECT
 * branch.</p>
 *
 * <p>Subclasses implement {@link #evaluate(LoomMedia, Map)} to return {@code true}
 * (pass) or {@code false} (reject).</p>
 */
public abstract class AbstractFilterNode extends AbstractPipelineNode {

	private static final Logger log = LoggerFactory.getLogger(AbstractFilterNode.class);

	protected AbstractFilterNode(String id, String name) {
		super(id, name, NodeMode.SEQUENTIAL, true, 1);
	}

	protected AbstractFilterNode(String id, String name, int concurrency) {
		super(id, name, NodeMode.PARALLEL, true, concurrency);
	}

	@Override
	public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		long start = System.currentTimeMillis();
		try {
			boolean passed = evaluate(media, upstreamResults);
			long elapsed = System.currentTimeMillis() - start;
			log.debug("Filter {} {} media {}", id(), passed ? "PASSED" : "REJECTED", media.absolutePath());
			return NodeResult.success(id(), elapsed, Map.of(
					FILTER_PASSED, passed,
					"filter_reason", passed ? "passed" : rejectReason(media, upstreamResults)));
		} catch (Exception e) {
			long elapsed = System.currentTimeMillis() - start;
			log.error("Filter {} failed on {}: {}", id(), media.absolutePath(), e.getMessage(), e);
			return NodeResult.failed(id(), elapsed, e.getMessage());
		}
	}

	/**
	 * Evaluate the filter condition.
	 *
	 * @param media           the media item to test
	 * @param upstreamResults results from dependency nodes
	 * @return {@code true} if the media passes the filter, {@code false} if rejected
	 */
	protected abstract boolean evaluate(LoomMedia media, Map<String, NodeResult> upstreamResults);

	/**
	 * Optional human-readable reason when the filter rejects. Subclasses may override
	 * to provide more detail.
	 */
	protected String rejectReason(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		return "rejected by " + id();
	}


}
