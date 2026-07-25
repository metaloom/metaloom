package io.metaloom.loom.db.model.memory;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface MemoryDenyRuleDao extends CRUDDao<MemoryDenyRule> {

	default MemoryDenyRule createMemoryDenyRule(User user, String name, String pattern, String message) {
		return createMemoryDenyRule(user.getUuid(), name, pattern, message);
	}

	MemoryDenyRule createMemoryDenyRule(UUID userUuid, String name, String pattern, String message);

	/**
	 * Load the rule with the given name, or {@code null}. Names are unique across the instance.
	 */
	MemoryDenyRule loadByName(String name);

	/**
	 * The rules that are actually applied to a write, ordered by name so rejection is deterministic when several match.
	 */
	List<MemoryDenyRule> loadEnabled();

}
