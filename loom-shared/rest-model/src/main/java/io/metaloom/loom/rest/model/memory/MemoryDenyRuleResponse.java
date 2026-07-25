package io.metaloom.loom.rest.model.memory;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class MemoryDenyRuleResponse extends AbstractCreatorEditorRestResponse<MemoryDenyRuleResponse>
	implements MemoryDenyRuleModel<MemoryDenyRuleResponse> {

	private String name;

	private String pattern;

	private String message;

	private Boolean enabled;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public MemoryDenyRuleResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getPattern() {
		return pattern;
	}

	@Override
	public MemoryDenyRuleResponse setPattern(String pattern) {
		this.pattern = pattern;
		return this;
	}

	@Override
	public String getMessage() {
		return message;
	}

	@Override
	public MemoryDenyRuleResponse setMessage(String message) {
		this.message = message;
		return this;
	}

	@Override
	public Boolean getEnabled() {
		return enabled;
	}

	@Override
	public MemoryDenyRuleResponse setEnabled(Boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public MemoryDenyRuleResponse self() {
		return this;
	}
}
