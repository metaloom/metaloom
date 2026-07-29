package io.metaloom.cortex.pipeline.core.node.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

/**
 * Base class for filter nodes that produce a Y-branch in the pipeline.
 *
 * <p>A filter node evaluates a condition on the media item (and optionally on its declared
 * input ports) and emits the {@link #OUT_PASSED} verdict. Downstream nodes can use
 * {@link PipelineNode#conditionalDependencies()} to bind to either the PASS or REJECT
 * branch.</p>
 *
 * <p>Subclasses implement {@link #evaluate(NodeContext)} to return {@code true}
 * (pass) or {@code false} (reject). They read whatever they need through the context's
 * typed input ports - the branch decision is far too load-bearing to hang off an untyped
 * lookup keyed by a node name the pipeline author chose.</p>
 */
public abstract class AbstractFilterNode extends AbstractPipelineNode {

	private static final Logger log = LoggerFactory.getLogger(AbstractFilterNode.class);

	/**
	 * The verdict every filter emits. Branch routing finds it by its {@code control/filter}
	 * content type rather than by name, so the id here is the descriptors' {@code passed} and
	 * nothing depends on it staying that.
	 */
	public static final OutputPort<Boolean> OUT_PASSED =
		OutputPort.one(PipelineNode.FILTER_PASSED, ContentTypeRegistry.CONTROL_FILTER, Boolean.class);

	protected AbstractFilterNode(String id, String name) {
		super(id, name, NodeMode.SEQUENTIAL, true, 1);
	}

	protected AbstractFilterNode(String id, String name, int concurrency) {
		super(id, name, NodeMode.PARALLEL, true, concurrency);
	}

	@Override
	public NodeResult process(LoomMedia media, NodeInputs inputs) {
		long start = System.currentTimeMillis();
		NodeContext<LoomMedia> ctx = NodeContext.create(media, inputs == null ? NodeInputs.empty() : inputs);
		try {
			boolean passed = evaluate(ctx);
			long elapsed = System.currentTimeMillis() - start;
			ctx.output(OUT_PASSED, passed);
			if (!passed) {
				// The reason is a log line rather than an output port: the descriptors give a
				// filter one output, and inventing an undeclared second one would fail the
				// boundary check that exists to catch exactly that.
				log.debug("Filter {} REJECTED media {}: {}", id(), media.absolutePath(), rejectReason(ctx));
			} else {
				log.debug("Filter {} PASSED media {}", id(), media.absolutePath());
			}
			return NodeResult.success(id(), elapsed, ctx.outputs());
		} catch (Exception e) {
			long elapsed = System.currentTimeMillis() - start;
			log.error("Filter {} failed on {}: {}", id(), media.absolutePath(), e.getMessage(), e);
			return NodeResult.failed(id(), elapsed, e.getMessage(), ctx.outputs());
		}
	}

	/**
	 * Evaluate the filter condition.
	 *
	 * @param ctx the context, carrying the media and this node's typed input ports
	 * @return {@code true} if the media passes the filter, {@code false} if rejected
	 */
	protected abstract boolean evaluate(NodeContext<LoomMedia> ctx);

	/**
	 * Optional human-readable reason when the filter rejects. Subclasses may override
	 * to provide more detail.
	 */
	protected String rejectReason(NodeContext<LoomMedia> ctx) {
		return "rejected by " + id();
	}

}
