package io.metaloom.loom.db.jooq.dao.pipeline;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.vertx.core.json.JsonObject;

public class PipelineImpl extends AbstractEditableElement<Pipeline> implements Pipeline {

	private String name;
	private String description;
	private JsonObject definition;
	private boolean enabled = true;
	private int priority;
	private boolean dryRun;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Pipeline setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public Pipeline setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public JsonObject getDefinition() {
		return definition;
	}

	@Override
	public Pipeline setDefinition(JsonObject definition) {
		this.definition = definition;
		return this;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public Pipeline setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public int getPriority() {
		return priority;
	}

	@Override
	public Pipeline setPriority(int priority) {
		this.priority = priority;
		return this;
	}

	@Override
	public boolean isDryRun() {
		return dryRun;
	}

	@Override
	public Pipeline setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

}
