package io.metaloom.cortex.pipeline.core.node;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.action.ActionResult;
import io.metaloom.cortex.api.action.FilesystemAction;
import io.metaloom.cortex.api.action.context.ActionContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;

/**
 * Adapter that wraps an existing {@link FilesystemAction} as a {@link io.metaloom.cortex.pipeline.api.node.PipelineNode}.
 * This allows the new pipeline system to reuse all existing Cortex actions.
 */
public class ActionNodeAdapter extends AbstractPipelineNode {

	private static final Logger log = LoggerFactory.getLogger(ActionNodeAdapter.class);

	private final FilesystemAction<?> action;

	public ActionNodeAdapter(FilesystemAction<?> action, NodeMode mode, boolean blocking,
			Set<String> dependencies, int concurrency) {
		super(action.name(), action.name(), mode, blocking, dependencies, concurrency);
		this.action = action;
	}

	@Override
	public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		long start = System.currentTimeMillis();
		try {
			ActionContext ctx = ActionContext.create(media);
			ActionResult result = action.process(ctx);
			long elapsed = System.currentTimeMillis() - start;
			if (result == null) {
				return NodeResult.failed(id(), elapsed, "Action returned null result");
			}
			switch (result.getState()) {
				case SUCCESS:
					return NodeResult.success(id(), elapsed);
				case SKIPPED:
					return NodeResult.skipped(id(), "Action skipped");
				case FAILED:
				default:
					return NodeResult.failed(id(), elapsed, "Action failed");
			}
		} catch (Exception e) {
			long elapsed = System.currentTimeMillis() - start;
			log.error("Error executing action node {}: {}", id(), e.getMessage(), e);
			return NodeResult.failed(id(), elapsed, e.getMessage());
		}
	}

	@Override
	public void initialize() {
		action.initialize();
	}

	public FilesystemAction<?> getAction() {
		return action;
	}
}
