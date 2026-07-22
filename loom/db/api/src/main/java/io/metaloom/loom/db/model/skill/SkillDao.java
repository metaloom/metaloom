package io.metaloom.loom.db.model.skill;

import java.util.List;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface SkillDao extends CRUDDao<Skill> {

	default Skill createSkill(User user, String name, String description, String content) {
		return createSkill(user.getUuid(), name, description, content);
	}

	Skill createSkill(UUID userUuid, String name, String description, String content);

	/**
	 * Load a page of skills which are owned by the given user.
	 */
	Page<Skill> findByCreator(UUID userUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	/**
	 * Load all skills for the given uuids. Unknown uuids are silently omitted.
	 */
	List<Skill> loadByUuids(List<UUID> uuids);

	/**
	 * Load a page of published skills (the shared skill library).
	 */
	Page<Skill> findPublished(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	/**
	 * Load the skill of the given owner with the given name.
	 */
	Skill loadByName(UUID userUuid, String name);

}
