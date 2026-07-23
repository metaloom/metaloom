package io.metaloom.loom.db.model.skill;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

public interface Skill extends CUDElement<Skill>, MetaElement<Skill> {

	String getName();

	Skill setName(String name);

	String getDescription();

	Skill setDescription(String description);

	String getContent();

	Skill setContent(String content);

	boolean isEnabled();

	Skill setEnabled(boolean enabled);

	boolean isPublished();

	Skill setPublished(boolean published);

	UUID getOriginSkillUuid();

	Skill setOriginSkillUuid(UUID originSkillUuid);

	/**
	 * Reference to the currently active {@link SkillVersion}.
	 */
	UUID getActiveVersionUuid();

	Skill setActiveVersionUuid(UUID activeVersionUuid);

	/**
	 * Version number of the currently active version. This is a transient, read-only projection populated when the skill is loaded together with its
	 * active version; it is not persisted on the skill row.
	 */
	int getActiveVersionNumber();

	Skill setActiveVersionNumber(int activeVersionNumber);

}
