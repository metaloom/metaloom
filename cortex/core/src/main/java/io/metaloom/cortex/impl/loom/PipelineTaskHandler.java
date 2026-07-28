package io.metaloom.cortex.impl.loom;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.media.MediaReferenceResolver;
import io.metaloom.cortex.common.metrics.CortexMetrics;
import io.metaloom.cortex.pipeline.api.node.MediaSourceNode;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.loader.NodeFactory;
import io.metaloom.cortex.runtime.NodeTaskRunner;
import io.metaloom.cortex.runtime.SegmentTaskRunner;
import io.metaloom.cortex.runtime.SourceTaskRunner;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.pipeline.model.SegmentTaskResult;
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
	private final CortexMetrics metrics;
	private final NodeTaskRunner nodeTaskRunner;
	private final SegmentTaskRunner segmentTaskRunner;
	private final io.metaloom.cortex.runtime.ResultBatcher resultBatcher;

	/** How often partially-filled result batches are swept. */
	private static final long BATCH_FLUSH_INTERVAL_MS = 250;
	private final SourceTaskRunner sourceTaskRunner;

	/** Sends a message back to Loom. */
	@FunctionalInterface
	public interface MessageSender {
		void send(ProcessorMessage message);
	}

	@Inject
	public PipelineTaskHandler(NodeFactory nodeFactory, LoomMediaLoader mediaLoader,
		MediaReferenceResolver mediaReferenceResolver, CortexMetrics metrics) {
		this.nodeFactory = nodeFactory;
		this.mediaLoader = mediaLoader;
		this.metrics = metrics;
		// Media arrives as a reference rather than a path, so that a worker can resolve remote
		// media (s3://...) on its own instead of requiring a shared mount. The default resolver
		// handles local paths exactly as before.
		this.nodeTaskRunner = new NodeTaskRunner(nodeFactory::createNode, mediaReferenceResolver::resolve);
		// Same factory and resolver: a segment is the same work with N > 1, so it
		// must resolve nodes and media exactly as a single task does.
		this.segmentTaskRunner = new SegmentTaskRunner(nodeFactory::createNode, mediaReferenceResolver::resolve);
		// The sink is supplied per call, because the connection to answer on is a
		// property of the task, not of this handler.
		this.resultBatcher = new io.metaloom.cortex.runtime.ResultBatcher();
		// A run's tail never reaches the batch size, so without this the last results
		// of every batched run are never sent and the run cannot close. The size
		// trigger is the optimisation; this timer is what makes batching correct.
		Schedulers.io().schedulePeriodicallyDirect(this::flushExpiredResults,
			BATCH_FLUSH_INTERVAL_MS, BATCH_FLUSH_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
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
		metrics.recordTaskReceived("node");
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
			recordNodeOutcome("node", task.getNodeKind(), result);
			// Batched when the pipeline asks for it; sent immediately when it does not.
			resultBatcher.add(task.getRunUuid(), task.getItemId(), result, task.getResultBatchSize(),
				batch -> sender.send(new ProcessorMessage(ProcessorMessageType.NODE_TASK_RESULT_BATCH,
					JsonObject.mapFrom(batch))));
		});
	}

	/**
	 * Send any result batch that has waited long enough.
	 *
	 * <p>Never throws: a periodic task that throws is cancelled permanently, which
	 * would silently strand the tail of every batched run from then on.</p>
	 */
	private void flushExpiredResults() {
		try {
			resultBatcher.flushExpired();
		} catch (Exception e) {
			log.error("Failed to flush pending result batches", e);
		}
	}

	/**
	 * Run a whole affinity segment and answer with one result per node.
	 *
	 * <p>Same shape as {@link #handleNodeTask}, and off the connection thread for the
	 * same reason - more so here, since a segment is by construction longer-running
	 * than a single node.</p>
	 *
	 * @param task   the segment
	 * @param sender used to send the result
	 */
	public void handleSegmentTask(SegmentTask task, MessageSender sender) {
		metrics.recordTaskReceived("segment");
		Schedulers.io().scheduleDirect(() -> {
			SegmentTaskResult result;
			try {
				result = segmentTaskRunner.run(task);
			} catch (Throwable t) {
				// The runner converts per-node failures itself, so reaching here means
				// something unexpected. Still answer: a silent drop stalls every node in
				// the segment, not just one.
				log.error("Unexpected failure running {}", task, t);
				result = new SegmentTaskResult(task.getTaskUuid(), task.getRunUuid(), task.getItemId(),
					task.getSegmentId(), java.util.List.of(), String.valueOf(t));
			}
			metrics.recordTaskCompleted("segment", result.getError() == null ? "success" : "failed");
			// Each node in the segment settles independently; record one node operation per result.
			for (NodeTaskResult nodeResult : result.getResults()) {
				metrics.recordNodeOperation(nodeResult.getNodeId(), toResultState(nodeResult.getState()), nodeResult.getDurationMs());
			}
			sender.send(new ProcessorMessage(ProcessorMessageType.SEGMENT_TASK_RESULT,
				JsonObject.mapFrom(result)));
		});
	}

	/** Record the per-node outcome of a single node task: task completion, duration and node operation. */
	private void recordNodeOutcome(String taskType, String nodeKind, NodeTaskResult result) {
		metrics.recordTaskCompleted(taskType, stateLabel(result.getState()));
		metrics.recordTaskDuration(taskType, result.getDurationMs());
		metrics.recordNodeOperation(nodeKind, toResultState(result.getState()), result.getDurationMs());
	}

	private static String stateLabel(NodeState state) {
		return switch (state) {
			case COMPLETED -> "success";
			case FAILED -> "failed";
			case SKIPPED -> "skipped";
			default -> state.name().toLowerCase();
		};
	}

	private static ResultState toResultState(NodeState state) {
		return switch (state) {
			case FAILED -> ResultState.FAILED;
			case SKIPPED -> ResultState.SKIPPED;
			default -> ResultState.SUCCESS;
		};
	}

	/**
	 * Run a source node and stream its output back in acknowledged batches.
	 *
	 * @param task   the source task
	 * @param sender used to send batches and the completion signal
	 */
	public void handleSourceTask(SourceTaskMessage task, MessageSender sender) {
		metrics.recordTaskReceived("source");
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
					metrics.recordSourceItemsEnumerated(totalCount);
					metrics.recordTaskCompleted("source", error == null ? "success" : "failed");
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
