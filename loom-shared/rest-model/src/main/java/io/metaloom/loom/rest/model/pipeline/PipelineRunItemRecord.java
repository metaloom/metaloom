package io.metaloom.loom.rest.model.pipeline;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.api.pipeline.RunItemState;
import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * A single pipeline run item - one media entry discovered and processed by a run.
 */
public class PipelineRunItemRecord implements RestResponseModel<PipelineRunItemRecord> {

	@JsonPropertyDescription("Unique identifier of the pipeline run item.")
	private UUID uuid;

	@JsonPropertyDescription("UUID of the pipeline run this item belongs to.")
	private UUID runUuid;

	@JsonPropertyDescription("Sequence number of the item within the run.")
	private long itemSeq;

	@JsonPropertyDescription("Path of the media handled by this item.")
	private String mediaPath;

	@JsonPropertyDescription("SHA-512 hash of the media, null if not yet computed.")
	private String sha512;

	@JsonPropertyDescription("Size of the media in bytes, null if unknown.")
	private Long sizeBytes;

	@JsonPropertyDescription("Current state: PENDING, RUNNING, SUCCESS, FAILED or SKIPPED.")
	private RunItemState state;

	@JsonPropertyDescription("Error message if the item failed.")
	private String errorMessage;

	public PipelineRunItemRecord() {
	}

	public UUID getUuid() {
		return uuid;
	}

	public PipelineRunItemRecord setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public UUID getRunUuid() {
		return runUuid;
	}

	public PipelineRunItemRecord setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public long getItemSeq() {
		return itemSeq;
	}

	public PipelineRunItemRecord setItemSeq(long itemSeq) {
		this.itemSeq = itemSeq;
		return this;
	}

	public String getMediaPath() {
		return mediaPath;
	}

	public PipelineRunItemRecord setMediaPath(String mediaPath) {
		this.mediaPath = mediaPath;
		return this;
	}

	public String getSha512() {
		return sha512;
	}

	public PipelineRunItemRecord setSha512(String sha512) {
		this.sha512 = sha512;
		return this;
	}

	public Long getSizeBytes() {
		return sizeBytes;
	}

	public PipelineRunItemRecord setSizeBytes(Long sizeBytes) {
		this.sizeBytes = sizeBytes;
		return this;
	}

	public RunItemState getState() {
		return state;
	}

	public PipelineRunItemRecord setState(RunItemState state) {
		this.state = state;
		return this;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public PipelineRunItemRecord setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	@Override
	public PipelineRunItemRecord self() {
		return this;
	}

}
