package io.metaloom.loom.rest.service.impl;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.pipeline.engine.NodeDispatcher;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry.ConnectedProcessor;

/**
 * Sends {@link NodeTask}s to connected Cortex processors over the processor
 * WebSocket.
 *
 * <p>The production implementation of {@link NodeDispatcher}. The engine depends on
 * the interface rather than on this class, which is what lets the whole evaluation
 * model be tested without a socket, a worker, or a database.</p>
 *
 * <p><strong>Phase 1 limitations, deliberately.</strong> Worker selection is the
 * existing capability-and-priority pick, which ignores live load and cannot yet
 * route by node kind - the node capability whitelist that would make that possible
 * is a separate work item. Dispatch is fire-and-forget: there is no lease, no
 * timeout and no retry, so a worker that accepts a task and then dies leaves that
 * node outstanding until the run is failed by other means. Both are Phase 2
 * concerns and are listed as such in the plan.</p>
 */
@Singleton
public class WebSocketNodeDispatcher implements NodeDispatcher {

	private static final Logger log = LoggerFactory.getLogger(WebSocketNodeDispatcher.class);

	private final ProcessorRegistry registry;

	@Inject
	public WebSocketNodeDispatcher(ProcessorRegistry registry) {
		this.registry = registry;
	}

	@Override
	public String dispatch(NodeTask task) {
		// Capability is still CPU for everything, because no node kind declares what
		// hardware it needs. The node-kind whitelist is the part that works: a worker
		// restricted to particular kinds will only be offered those.
		ConnectedProcessor processor = registry.selectProcessor(ProcessorCapability.CPU, task.getNodeKind());
		if (processor == null) {
			log.warn("No online processor accepts node kind '{}' for {}", task.getNodeKind(), task);
			return null;
		}

		boolean sent = registry.send(processor.nodeId, ProcessorMessageType.NODE_TASK, task);
		if (!sent) {
			// The socket closed between selection and write. Reporting false lets the
			// engine settle the node rather than wait for a result that cannot arrive.
			log.warn("Processor '{}' went away before {} could be sent", processor.nodeId, task);
			return null;
		}
		if (log.isDebugEnabled()) {
			log.debug("Dispatched {} to processor '{}'", task, processor.nodeId);
		}
		return processor.nodeId;
	}

	@Override
	public String dispatch(SegmentTask task) {
		// A segment needs one worker permitted to run *every* kind in it. "Some worker
		// runs each kind" is not enough - a fleet with one decode worker and one
		// facedetect worker satisfies both kinds and can still not run a segment
		// spanning them.
		ConnectedProcessor processor = registry.selectProcessorForKinds(ProcessorCapability.CPU, task.getNodeKinds());
		if (processor == null) {
			log.debug("No online processor accepts all of {} for {}", task.getNodeKinds(), task);
			return null;
		}

		boolean sent = registry.send(processor.nodeId, ProcessorMessageType.SEGMENT_TASK, task);
		if (!sent) {
			log.warn("Processor '{}' went away before {} could be sent", processor.nodeId, task);
			return null;
		}
		return processor.nodeId;
	}
}
