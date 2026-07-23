package io.metaloom.loom.db.model.skill;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.page.Page;
import io.vertx.core.json.JsonObject;

public interface SkillVersionDao extends CRUDDao<SkillVersion> {

	/**
	 * Load all versions of the given skill, ordered by version number ascending.
	 */
	List<SkillVersion> loadBySkill(UUID skillUuid);

	/**
	 * Load skill versions by their UUIDs in a single query. Used to resolve the active version of many skills at once without an N+1 lookup.
	 *
	 * @return the matching versions, in no particular order; unknown UUIDs are silently skipped
	 */
	List<SkillVersion> loadByUuids(Collection<UUID> uuids);

	/**
	 * Load a paged list of versions for the given skill.
	 */
	Page<SkillVersion> loadPageBySkill(UUID skillUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	/**
	 * Load the latest (highest version number) version of the given skill.
	 */
	SkillVersion loadLatestBySkill(UUID skillUuid);

	/**
	 * Load a specific version of the given skill.
	 */
	SkillVersion loadBySkillAndVersion(UUID skillUuid, int versionNumber);

	/**
	 * Create a new (unpersisted) skill version. The caller is responsible for {@code store()}ing it.
	 */
	SkillVersion createVersion(UUID userUuid, UUID skillUuid, int versionNumber, String description, String content, JsonObject meta);

	/**
	 * Delete all versions of the given skill whose version number is greater than the given one. Used when reverting to an earlier version.
	 *
	 * @return the number of deleted versions
	 */
	int deleteVersionsAfter(UUID skillUuid, int versionNumber);

}
