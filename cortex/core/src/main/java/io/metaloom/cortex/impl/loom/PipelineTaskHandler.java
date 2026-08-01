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
import io.metaloom.loom.rest.model.processor.message.TaskReturnedMessage;
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

	/** How often {@link #awaitDrain(long)} re-checks whether the worker has gone quiet. */
	private static final long DRAIN_POLL_INTERVAL_MS = 100;

	/**
	 * Node and segment tasks currently executing, by task uuid.
	 *
	 * <p>Tracked solely so a shutting-down worker can say what it will not finish.
	 * Without this the only way Loom learns is the lease lapsing, which costs the run a
	 * full lease interval per task.</p>
	 */
	private final java.util.Map<UUID, InFlightTask> inFlight = new java.util.concurrent.ConcurrentHashMap<>();

	/** Runs whose source enumeration is executing here. Waited for, but never returned. */
	private final java.util.Set<UUID> activeSources = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Set once this worker has announced {@code TERMINATING}. Loom stops placing work on
	 * a worker in that state, but a task already on the wire when the announcement went
	 * out still arrives - this is what makes those an immediate hand-back rather than
	 * work started seconds before the process exits.
	 */
	private volatile boolean draining;

	/** Sends a message back to Loom. */
	@FunctionalInterface
	public interface MessageSender {
		void send(ProcessorMessage message);
	}

	/**
	 * A dispatched task this worker is holding, and the connection to answer on.
	 *
	 * @param taskUuid   the dispatch
	 * @param runUuid    the run it belongs to
	 * @param itemId     the media item
	 * @param nodeIds    graph-local node ids - one for a node task, all members for a segment
	 * @param elementSeq which element of a fanned-out sequence; 0 for a node that runs once
	 * @param sender     the connection the task arrived on
	 */
	private record InFlightTask(UUID taskUuid, UUID runUuid, String itemId, List<String> nodeIds,
		int elementSeq, MessageSender sender) {
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
		InFlightTask entry = new InFlightTask(task.getTaskUuid(), task.getRunUuid(), task.getItemId(),
			List.of(task.getNodeId()), task.getElementSeq(), sender);
		if (draining) {
			// Started now, this would be abandoned within seconds. Handing it straight
			// back is what lets Loom place it on a live worker immediately.
			returnTask(entry, "refused", "worker is shutting down");
			return;
		}
		inFlight.put(task.getTaskUuid(), entry);
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
			// Released before the answer goes out, so a drain running concurrently cannot
			// hand back work that has in fact been done.
			inFlight.remove(task.getTaskUuid());
			recordNodeOutcome("node", task.getNodeKind(), result);
			// Batched when the pipeline asks for it; sent immediately when it does not.
			resultBatcher.add(task.getRunUuid(), task.getItemId(), result, task.getResultBatchSize(),
				batch -> sender.send(new ProcessorMessage(ProcessorMessageType.NODE_TASK_RESULT_BATCH,
					JsonObject.mapFrom(batch))));
		});
	}

	/**
	 * Stop accepting work, so that everything still outstanding is a finite set.
	 *
	 * <p>Call after announcing {@code TERMINATING}, not before: between the two this
	 * worker is refusing work Loom still believes it will do, and every such task pays a
	 * round trip.</p>
	 *
	 * @return how many tasks were executing when the drain began
	 */
	public int beginDrain() {
		draining = true;
		return inFlight.size();
	}

	/** @return true once this worker has stopped accepting new tasks */
	public boolean isDraining() {
		return draining;
	}

	/** @return how many node and segment tasks are executing right now */
	public int inFlightCount() {
		return inFlight.size();
	}

	/**
	 * Wait for the tasks already running to finish.
	 *
	 * <p>Blocking, and deliberately so - it is called from the shutdown path rather than
	 * from the connection's event loop. Source enumeration counts as outstanding here
	 * too: a scan that is nearly done should be allowed to finish, since unlike a node
	 * task there is nothing Loom can do to recover a half-enumerated source.</p>
	 *
	 * @param timeoutMs how long to wait before giving up on the rest
	 * @return how many tasks were still running when the wait ended
	 */
	public int awaitDrain(long timeoutMs) {
		long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
		while (!inFlight.isEmpty() || !activeSources.isEmpty()) {
			if (System.currentTimeMillis() >= deadline) {
				break;
			}
			try {
				Thread.sleep(DRAIN_POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				// A shutdown that is itself being hurried along. Stop waiting, but let the
				// caller still hand back what is outstanding.
				Thread.currentThread().interrupt();
				break;
			}
		}
		return inFlight.size();
	}

	/**
	 * Hand every task still outstanding back to Loom.
	 *
	 * <p>The tasks keep running - a node cannot be interrupted mid-way - but Loom is
	 * free to place them elsewhere at once rather than waiting out their leases. If one
	 * of them does finish after all, its result settles the node and the re-dispatched
	 * copy is recognised as a duplicate, which is the trade the lease model already
	 * makes.</p>
	 *
	 * @param reason recorded on the re-dispatch
	 * @return how many tasks were handed back
	 */
	public int returnOutstanding(String reason) {
		int returned = 0;
		for (UUID taskUuid : List.copyOf(inFlight.keySet())) {
			InFlightTask entry = inFlight.remove(taskUuid);
			if (entry != null) {
				returnTask(entry, "unfinished", reason);
				returned++;
			}
		}
		if (!activeSources.isEmpty()) {
			// Nothing to hand back: a source task has no reclaim path, and fabricating a
			// SOURCE_COMPLETE would mark a truncated scan as a whole one. The run waits for
			// an enumeration that will not resume, which is a gap worth naming rather than
			// papering over.
			log.warn("Abandoning source enumeration for run(s) {} - those runs will not complete until "
				+ "their source is dispatched again", activeSources);
		}
		return returned;
	}

	/** Tell Loom a task will not be executed here, so it can be placed elsewhere now. */
	private void returnTask(InFlightTask entry, String reason, String detail) {
		metrics.recordTaskReturned(reason);
		entry.sender().send(new ProcessorMessage(ProcessorMessageType.TASK_RETURNED,
			JsonObject.mapFrom(new TaskReturnedMessage()
				.setRunUuid(entry.runUuid())
				.setItemId(entry.itemId())
				.setTaskUuid(entry.taskUuid())
				.setNodeIds(entry.nodeIds())
				.setElementSeq(entry.elementSeq())
				.setReason(detail))));
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
		// Every node of the segment is handed back together: the whole unit was placed
		// here, and a segment exists precisely so its members are not split up.
		InFlightTask entry = new InFlightTask(task.getTaskUuid(), task.getRunUuid(), task.getItemId(),
			task.getNodes().stream().map(io.metaloom.loom.pipeline.model.SegmentNode::getNodeId).toList(),
			0, sender);
		if (draining) {
			returnTask(entry, "refused", "worker is shutting down");
			return;
		}
		inFlight.put(task.getTaskUuid(), entry);
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
			// Released before the answer goes out, for the same reason as a node task.
			inFlight.remove(task.getTaskUuid());
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
		if (draining) {
			// A source cannot be handed back the way a node task can, so refusing it is
			// the only honest answer: say the enumeration produced nothing and why, and
			// let the run be started again against a live worker.
			log.warn("Refusing source task for run {}: this worker is shutting down", task.getRunUuid());
			metrics.recordTaskReturned("refused");
			sender.send(completeMessage(task.getRunUuid(), 0, "worker is shutting down"));
			return;
		}
		activeSources.add(task.getRunUuid());
		Schedulers.io().scheduleDirect(() -> {
			UUID runUuid = task.getRunUuid();
			Flowable<LoomMedia> stream;
			try {
				stream = resolveSourceStream(task);
			} catch (Exception e) {
				log.error("Cannot start source task for run {}", runUuid, e);
				activeSources.remove(runUuid);
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
					activeSources.remove(runUuid);
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
		activeSources.remove(runUuid);
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
