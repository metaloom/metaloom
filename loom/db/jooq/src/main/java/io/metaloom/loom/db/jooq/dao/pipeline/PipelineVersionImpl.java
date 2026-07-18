package io.metaloom.loom.db.jooq.dao.pipeline;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.vertx.core.json.JsonObject;

public class PipelineVersionImpl extends AbstractEditableElement<PipelineVersion> implements PipelineVersion {

	private UUID pipelineUuid;
	private int versionNumber;
	private String name;
	private String description;
	private JsonObject definition;
	private boolean enabled = true;
	private int priority;
	private boolean dryRun;
	private JsonObject meta;

	@Override
	public UUID getPipelineUuid() {
		return pipelineUuid;
	}

	@Override
	public PipelineVersion setPipelineUuid(UUID pipelineUuid) {
		this.pipelineUuid = pipelineUuid;
		return this;
	}

	@Override
	public int getVersionNumber() {
		return versionNumber;
	}

	@Override
	public PipelineVersion setVersionNumber(int versionNumber) {
		this.versionNumber = versionNumber;
		return this;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public PipelineVersion setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public PipelineVersion setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public JsonObject getDefinition() {
		return definition;
	}

	@Override
	public PipelineVersion setDefinition(JsonObject definition) {
		this.definition = definition;
		return this;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public PipelineVersion setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public int getPriority() {
		return priority;
	}

	@Override
	public PipelineVersion setPriority(int priority) {
		this.priority = priority;
		return this;
	}

	@Override
	public boolean isDryRun() {
		return dryRun;
	}

	@Override
	public PipelineVersion setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public PipelineVersion setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}