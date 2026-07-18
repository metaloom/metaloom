package io.metaloom.loom.db.jooq.dao.pipeline;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.vertx.core.json.JsonObject;

public class PipelineImpl extends AbstractEditableElement<Pipeline> implements Pipeline {

	private UUID latestVersionUuid;
	private JsonObject meta;

	@Override
	public UUID getLatestVersionUuid() {
		return latestVersionUuid;
	}

	@Override
	public Pipeline setLatestVersionUuid(UUID latestVersionUuid) {
		this.latestVersionUuid = latestVersionUuid;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public Pipeline setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}
