package io.metaloom.cortex.impl.loom;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.pipeline.api.node.MediaSourceNode;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.loader.NodeFactory;
import io.metaloom.cortex.runtime.NodeTaskRunner;
import io.metaloom.cortex.runtime.SourceTaskRunner;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.rest.model.processor.message.NodeTaskResultMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.SourceCompleteMessage;
import io.metaloom.loom.rest.model.processor.message.SourceItemsMessage;
import io.metaloom.loom.rest.model.processor.message.SourceTaskMessage;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.vertx.core.json.JsonObject;

/**
 * Handles the Variant C task messages: run one node, or run a source and stream
 * what it finds.
 *
 * <p>Cortex does not decide what runs next here - Loom does. This class only turns
 * an inbound task into a node invocation and an outbound result.</p>
 *
 * <p>All work is moved off the WebSocket thread onto {@link Schedulers#io()}: a
 * node can take seconds (hashing) to minutes (transcription), and blocking the
 * connection would stall heartbeats and every other task.</p>
 */
@Singleton
public class PipelineTaskHandler {

	private static final Logger log = LoggerFactory.getLogger(PipelineTaskHandler.class);

	private final NodeFactory nodeFactory;
	private final LoomMediaLoader mediaLoader;
	private final NodeTaskRunner nodeTaskRunner;
	private final SourceTaskRunner sourceTaskRunner;

	/** Sends a message back to Loom. */
	@FunctionalInterface
	public interface MessageSender {
		void send(ProcessorMessage message);
	}

	@Inject
	public PipelineTaskHandler(NodeFactory nodeFactory, LoomMediaLoader mediaLoader) {
		this.nodeFactory = nodeFactory;
		this.mediaLoader = mediaLoader;
		this.nodeTaskRunner = new NodeTaskRunner(nodeFactory::createNode, mediaLoader::load);
		this.sourceTaskRunner = new SourceTaskRunner();
	}

	/**
	 * Execute a node task and report the outcome.
	 *
	 * <p>Returns immediately; the result is sent when the node finishes. Every task
	 * produces exactly one result message - including failures, because the engine
	 * blocks on an answer for each task it dispatched.</p>
	 *
	 * @param task   the work
	 * @param sender used to send the result
	 */
	public void handleNodeTask(NodeTask task, MessageSender sender) {
		Schedulers.io().scheduleDirect(() -> {
			NodeTaskResult result;
			try {
				result = nodeTaskRunner.run(task);
			} catch (Throwable t) {
				// NodeTaskRunner already converts exceptions, so reaching here means
				// something unexpected. Still answer - a silent drop stalls the run.
				log.error("Unexpected failure running {}", task, t);
				result = NodeTaskResult.failed(task.getTaskUuid(), task.getNodeId(), 0, String.valueOf(t));
			}
			sender.send(new ProcessorMessage(ProcessorMessageType.NODE_TASK_RESULT,
				JsonObject.mapFrom(new NodeTaskResultMessage()
					.setRunUuid(task.getRunUuid())
					.setItemId(task.getItemId())
					.setResult(result))));
		});
	}

	/**
	 * Run a source node and stream its output back in acknowledged batches.
	 *
	 * @param task   the source task
	 * @param sender used to send batches and the completion signal
	 */
	public void handleSourceTask(SourceTaskMessage task, MessageSender sender) {
		Schedulers.io().scheduleDirect(() -> {
			UUID runUuid = task.getRunUuid();
			Flowable<LoomMedia> stream;
			try {
				stream = resolveSourceStream(task);
			} catch (Exception e) {
				log.error("Cannot start source task for run {}", runUuid, e);
				sender.send(completeMessage(runUuid, 0, String.valueOf(e.getMessage())));
				return;
			}

			sourceTaskRunner.run(runUuid, stream, task.getBatchSize(), new SourceTaskRunner.BatchSink() {

				@Override
				public void sendBatch(long seq, List<MediaRef> items) {
					sender.send(new ProcessorMessage(ProcessorMessageType.SOURCE_ITEMS,
						JsonObject.mapFrom(new SourceItemsMessage()
							.setRunUuid(runUuid).setSeq(seq).setItems(items))));
				}

				@Override
				public void sendComplete(long totalCount, String error) {
					sender.send(completeMessage(runUuid, totalCount, error));
				}
			});
		});
	}

	/**
	 * Release the source runner to send its next batch.
	 *
	 * @param runUuid the run
	 * @param seq     the batch acknowledged
	 */
	public void handleSourceItemsAck(UUID runUuid, long seq) {
		sourceTaskRunner.onAck(runUuid, seq);
	}

	/**
	 * Abandon any source wait for this run, e.g. on disconnect.
	 *
	 * @param runUuid the run
	 */
	public void cancelSource(UUID runUuid) {
		sourceTaskRunner.cancel(runUuid);
	}

	/**
	 * Build the source node and take its stream.
	 *
	 * <p>The node is reused exactly as it exists - only its sink changes. Instead of
	 * feeding a local executor, the stream is forwarded over the wire.</p>
	 */
	private Flowable<LoomMedia> resolveSourceStream(SourceTaskMessage task) {
		JsonObject nodeDef = new JsonObject()
			.put("id", task.getNodeId())
			.put("type", task.getNodeKind());
		if (task.getOptions() != null) {
			task.getOptions().forEach(nodeDef::put);
		}

		PipelineNode node = nodeFactory.createNode(nodeDef);
		if (!(node instanceof MediaSourceNode)) {
			throw new IllegalArgumentException("Node kind '" + task.getNodeKind()
				+ "' is not a media source and cannot answer a SOURCE_TASK");
		}
		return ((MediaSourceNode) node).stream();
	}

	private ProcessorMessage completeMessage(UUID runUuid, long total, String error) {
		return new ProcessorMessage(ProcessorMessageType.SOURCE_COMPLETE,
			JsonObject.mapFrom(new SourceCompleteMessage()
				.setRunUuid(runUuid).setTotalCount(total).setError(error)));
	}
}
