package io.metaloom.loom.rest.model.pipeline;

import java.util.UUID;

import io.metaloom.loom.rest.model.MetaModel;
import io.vertx.core.json.JsonObject;

/**
 * Flattened pipeline model.
 *
 * <p>
 * A pipeline and its versions are two separate logical elements in the persistence layer (the {@code pipeline} and {@code pipeline_version} tables). The REST
 * API deliberately merges them into this single model so that clients never have to issue a second request just to learn a pipeline's name or definition.
 * </p>
 *
 * <p>
 * The {@code uuid} of the response always identifies the <em>pipeline</em>. The version the payload was rendered from is identified by {@link #getVersionUuid()}
 * and {@link #getVersionNumber()}.
 * </p>
 */
public interface PipelineModel<T extends PipelineModel<T>> extends MetaModel<T> {

	/**
	 * Reference to the pipeline version this model was rendered from.
	 */
	UUID getVersionUuid();

	T setVersionUuid(UUID versionUuid);

	Integer getVersionNumber();

	T setVersionNumber(Integer versionNumber);

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
