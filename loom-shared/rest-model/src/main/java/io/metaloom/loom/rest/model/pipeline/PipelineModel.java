package io.metaloom.loom.rest.model.pipeline;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

public interface PipelineModel<T extends PipelineModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

	String getDescription();

	T setDescription(String description);

	JsonObject getDefinition();

	T setDefinition(JsonObject definition);

	Boolean isEnabled();

	T setEnabled(Boolean enabled);

	Integer getPriority();

	T setPriority(Integer priority);

	Boolean isDryRun();

	T setDryRun(Boolean dryRun);

}
