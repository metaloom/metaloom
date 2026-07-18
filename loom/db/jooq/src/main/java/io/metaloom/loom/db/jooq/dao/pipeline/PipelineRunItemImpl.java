package io.metaloom.loom.db.jooq.dao.pipeline;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.vertx.core.json.JsonObject;

public class PipelineRunItemImpl extends AbstractEditableElement<PipelineRunItem> implements PipelineRunItem {

	private UUID runUuid;
	private long itemSeq;
	private String mediaPath;
	private String sha512;
	private Long sizeBytes;
	private String state = "PENDING";
	private String errorMessage;
	private JsonObject meta;

	@Override
	public UUID getRunUuid() {
		return runUuid;
	}

	@Override
	public PipelineRunItem setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	@Override
	public long getItemSeq() {
		return itemSeq;
	}

	@Override
	public PipelineRunItem setItemSeq(long itemSeq) {
		this.itemSeq = itemSeq;
		return this;
	}

	@Override
	public String getMediaPath() {
		return mediaPath;
	}

	@Override
	public PipelineRunItem setMediaPath(String mediaPath) {
		this.mediaPath = mediaPath;
		return this;
	}

	@Override
	public String getSha512() {
		return sha512;
	}

	@Override
	public PipelineRunItem setSha512(String sha512) {
		this.sha512 = sha512;
		return this;
	}

	@Override
	public Long getSizeBytes() {
		return sizeBytes;
	}

	@Override
	public PipelineRunItem setSizeBytes(Long sizeBytes) {
		this.sizeBytes = sizeBytes;
		return this;
	}

	@Override
	public String getState() {
		return state;
	}

	@Override
	public PipelineRunItem setState(String state) {
		this.state = state;
		return this;
	}

	@Override
	public String getErrorMessage() {
		return errorMessage;
	}

	@Override
	public PipelineRunItem setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public PipelineRunItem setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}
