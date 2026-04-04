package io.metaloom.loom.db.model.pipeline;

import io.metaloom.loom.db.CUDElement;
import io.vertx.core.json.JsonObject;

public interface Pipeline extends CUDElement<Pipeline> {

	String getName();

	Pipeline setName(String name);

	String getDescription();

	Pipeline setDescription(String description);

	JsonObject getDefinition();

	Pipeline setDefinition(JsonObject definition);

	boolean isEnabled();

	Pipeline setEnabled(boolean enabled);

	int getPriority();

	Pipeline setPriority(int priority);

	boolean isDryRun();

	Pipeline setDryRun(boolean dryRun);

}
