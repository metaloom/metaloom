package io.metaloom.loom.db.model.pipeline;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.vertx.core.json.JsonObject;

/**
 * One node execution against one media item.
 *
 * <p>The row is created when the engine decides a node is ready and updated as the
 * task is leased, completes, or is reclaimed. Because
 * {@code (item_uuid, node_id)} is unique, a duplicate delivery cannot produce a
 * second execution record - which is what makes retries safe.</p>
 */
public interface PipelineNodeTask extends CUDElement<PipelineNodeTask> {

	UUID getItemUuid();

	PipelineNodeTask setItemUuid(UUID itemUuid);

	/** @return denormalised from the item so run-scoped queries need no join */
	UUID getRunUuid();

	PipelineNodeTask setRunUuid(UUID runUuid);

	String getNodeId();

	PipelineNodeTask setNodeId(String nodeId);

	String getNodeKind();

	PipelineNodeTask setNodeKind(String nodeKind);

	/** @return PENDING, RUNNING, COMPLETED, FAILED, SKIPPED or DEAD_LETTER */
	String getState();

	PipelineNodeTask setState(String state);

	/**
	 * Which element of a fanned-out sequence this execution covers.
	 *
	 * <p>
	 * A node whose upstream port emits a sequence runs once per element, so {@code (item, node)} no
	 * longer identifies an execution - {@code (item, node, elementSeq)} does. A node that runs once
	 * per item keeps 0.
	 * </p>
	 *
	 * @return the element index, 0 when the node runs once per item
	 */
	int getElementSeq();

	PipelineNodeTask setElementSeq(int elementSeq);

	/**
	 * Which attempt at this execution the row records.
	 *
	 * <p>
	 * 0 for a node that ran once, which is every task an ordinary run produces. It counts up when an
	 * operator, having stopped the run at this node, changes a setting and asks for the same input to
	 * be processed again — each attempt keeping its own row so the two can be compared. Distinct from
	 * {@link #getAttempt()}, which counts <em>retries of the same attempt</em> after a failure and
	 * leaves no trace of what the failed try produced.
	 * </p>
	 *
	 * @return the generation, 0 when this execution has only ever run once
	 */
	int getGeneration();

	PipelineNodeTask setGeneration(int generation);

	/** @return how many times this task has been dispatched */
	int getAttempt();

	PipelineNodeTask setAttempt(int attempt);

	/** @return attempt ceiling before the task is dead-lettered */
	int getMaxAttempts();

	PipelineNodeTask setMaxAttempts(int maxAttempts);

	/** @return processor node id currently holding the task, or null */
	String getLeasedBy();

	PipelineNodeTask setLeasedBy(String leasedBy);

	/** @return when the lease lapses and the task returns to PENDING */
	Instant getLeaseExpiresAt();

	PipelineNodeTask setLeaseExpiresAt(Instant leaseExpiresAt);

	Instant getStarted();

	PipelineNodeTask setStarted(Instant started);

	Instant getFinished();

	PipelineNodeTask setFinished(Instant finished);

	Long getDurationMs();

	PipelineNodeTask setDurationMs(Long durationMs);

	String getErrorMessage();

	PipelineNodeTask setErrorMessage(String errorMessage);

	/** @return node outputs, consumed as inputs by downstream nodes */
	JsonObject getOutputs();

	PipelineNodeTask setOutputs(JsonObject outputs);

	/**
	 * Opt-in debugging previews of {@link #getOutputs()}, keyed by output port id.
	 *
	 * <p>
	 * Null for every run not started in debug mode, which is every ordinary one. Run-scoped
	 * diagnostics pruned with the run - never catalogue state.
	 * </p>
	 */
	JsonObject getPreviews();

	PipelineNodeTask setPreviews(JsonObject previews);

	JsonObject getMeta();

	PipelineNodeTask setMeta(JsonObject meta);

}
