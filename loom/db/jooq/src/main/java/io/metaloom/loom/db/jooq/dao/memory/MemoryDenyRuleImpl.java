package io.metaloom.loom.db.jooq.dao.memory;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.memory.MemoryDenyRule;
import io.vertx.core.json.JsonObject;

public class MemoryDenyRuleImpl extends AbstractEditableElement<MemoryDenyRule> implements MemoryDenyRule {

	private String name;

	private String pattern;

	private String message;

	private boolean enabled = true;

	private JsonObject meta;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public MemoryDenyRule setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getPattern() {
		return pattern;
	}

	@Override
	public MemoryDenyRule setPattern(String pattern) {
		this.pattern = pattern;
		return this;
	}

	@Override
	public String getMessage() {
		return message;
	}

	@Override
	public MemoryDenyRule setMessage(String message) {
		this.message = message;
		return this;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public MemoryDenyRule setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public MemoryDenyRule setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}
