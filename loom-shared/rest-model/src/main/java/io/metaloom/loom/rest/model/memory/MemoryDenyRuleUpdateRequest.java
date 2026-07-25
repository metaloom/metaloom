package io.metaloom.loom.rest.model.memory;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class MemoryDenyRuleUpdateRequest extends AbstractMetaModel<MemoryDenyRuleUpdateRequest>
	implements RestRequestModel, MemoryDenyRuleModel<MemoryDenyRuleUpdateRequest> {

	private String name;

	private String pattern;

	private String message;

	private Boolean enabled;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public MemoryDenyRuleUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getPattern() {
		return pattern;
	}

	@Override
	public MemoryDenyRuleUpdateRequest setPattern(String pattern) {
		this.pattern = pattern;
		return this;
	}

	@Override
	public String getMessage() {
		return message;
	}

	@Override
	public MemoryDenyRuleUpdateRequest setMessage(String message) {
		this.message = message;
		return this;
	}

	@Override
	public Boolean getEnabled() {
		return enabled;
	}

	@Override
	public MemoryDenyRuleUpdateRequest setEnabled(Boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public MemoryDenyRuleUpdateRequest self() {
		return this;
	}
}
