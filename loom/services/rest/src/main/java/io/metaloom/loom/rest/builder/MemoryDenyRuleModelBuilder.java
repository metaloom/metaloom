package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.memory.MemoryDenyRule;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.memory.MemoryDenyRuleListResponse;
import io.metaloom.loom.rest.model.memory.MemoryDenyRuleResponse;

public interface MemoryDenyRuleModelBuilder extends ModelBuilder, UserModelBuilder {

	default MemoryDenyRuleResponse toResponse(MemoryDenyRule rule) {
		MemoryDenyRuleResponse response = new MemoryDenyRuleResponse();
		response.setUuid(rule.getUuid());
		response.setName(rule.getName());
		response.setPattern(rule.getPattern());
		response.setMessage(rule.getMessage());
		response.setEnabled(rule.isEnabled());
		response.setMeta(rule.getMeta());
		setStatus(rule, response);
		return response;
	}

	default MemoryDenyRuleListResponse toMemoryDenyRuleList(Page<MemoryDenyRule> page) {
		return setPage(new MemoryDenyRuleListResponse(), page, this::toResponse);
	}
}
