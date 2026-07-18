package io.metaloom.loom.db.model.pipeline;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.vertx.core.json.JsonObject;

/**
 * One media item discovered by a run's source node.
 *
 * <p>An item is the unit a pipeline graph is evaluated over: it carries a
 * {@link PipelineNodeTask} per graph node. Persisting items is what lets a run
 * survive a Loom restart - the engine can rebuild its state by reloading the items
 * of an unfinished run and the tasks already settled against them.</p>
 */
public interface PipelineRunItem extends CUDElement<PipelineRunItem> {

	UUID getRunUuid();

	PipelineRunItem setRunUuid(UUID runUuid);

	/**
	 * @return discovery order within the run; unique per run, so re-delivery of a
	 *         source batch after a reconnect cannot duplicate the item
	 */
	long getItemSeq();

	PipelineRunItem setItemSeq(long itemSeq);

	String getMediaPath();

	PipelineRunItem setMediaPath(String mediaPath);

	String getSha512();

	PipelineRunItem setSha512(String sha512);

	/** @return size in bytes, or -1 when the source could not determine it */
	Long getSizeBytes();

	PipelineRunItem setSizeBytes(Long sizeBytes);

	/** @return PENDING, RUNNING, SUCCESS, FAILED or SKIPPED */
	String getState();

	PipelineRunItem setState(String state);

	String getErrorMessage();

	PipelineRunItem setErrorMessage(String errorMessage);

	JsonObject getMeta();

	PipelineRunItem setMeta(JsonObject meta);

}
