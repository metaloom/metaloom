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

}
