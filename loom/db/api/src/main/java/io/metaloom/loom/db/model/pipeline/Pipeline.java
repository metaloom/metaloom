package io.metaloom.loom.db.model.pipeline;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.vertx.core.json.JsonObject;

public interface Pipeline extends CUDElement<Pipeline> {

	UUID getLatestVersionUuid();

	Pipeline setLatestVersionUuid(UUID latestVersionUuid);

	JsonObject getMeta();

	Pipeline setMeta(JsonObject meta);

}
