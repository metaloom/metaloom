package io.metaloom.loom.db.model.pipeline;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.vertx.core.json.JsonObject;

public interface PipelineVersion extends CUDElement<PipelineVersion> {

	UUID getPipelineUuid();

	PipelineVersion setPipelineUuid(UUID pipelineUuid);

	int getVersionNumber();

	PipelineVersion setVersionNumber(int versionNumber);

	String getName();

	PipelineVersion setName(String name);

	String getDescription();

	PipelineVersion setDescription(String description);

	JsonObject getDefinition();

	PipelineVersion setDefinition(JsonObject definition);

	boolean isEnabled();

	PipelineVersion setEnabled(boolean enabled);

	int getPriority();

	PipelineVersion setPriority(int priority);

	boolean isDryRun();

	PipelineVersion setDryRun(boolean dryRun);

	JsonObject getMeta();

	PipelineVersion setMeta(JsonObject meta);

}