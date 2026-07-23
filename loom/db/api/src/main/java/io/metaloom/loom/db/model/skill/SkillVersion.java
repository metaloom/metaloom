package io.metaloom.loom.db.model.skill;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

/**
 * An immutable snapshot of a {@link Skill}'s versioned body (description + content).
 */
public interface SkillVersion extends CUDElement<SkillVersion>, MetaElement<SkillVersion> {

	UUID getSkillUuid();

	SkillVersion setSkillUuid(UUID skillUuid);

	int getVersionNumber();

	SkillVersion setVersionNumber(int versionNumber);

	String getDescription();

	SkillVersion setDescription(String description);

	String getContent();

	SkillVersion setContent(String content);

}
