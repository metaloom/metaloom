package io.metaloom.loom.rest.model.memory;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class MemoryDenyRuleCreateRequest extends AbstractMetaModel<MemoryDenyRuleCreateRequest>
	implements RestRequestModel, MemoryDenyRuleModel<MemoryDenyRuleCreateRequest> {

	private String name;

	private String pattern;

	private String message;

	private Boolean enabled;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public MemoryDenyRuleCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getPattern() {
		return pattern;
	}

	@Override
	public MemoryDenyRuleCreateRequest setPattern(String pattern) {
		this.pattern = pattern;
		return this;
	}

	@Override
	public String getMessage() {
		return message;
	}

	@Override
	public MemoryDenyRuleCreateRequest setMessage(String message) {
		this.message = message;
		return this;
	}

	@Override
	public Boolean getEnabled() {
		return enabled;
	}

	@Override
	public MemoryDenyRuleCreateRequest setEnabled(Boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public MemoryDenyRuleCreateRequest self() {
		return this;
	}
}
