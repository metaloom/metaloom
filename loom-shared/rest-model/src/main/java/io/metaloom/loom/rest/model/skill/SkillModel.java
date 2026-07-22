package io.metaloom.loom.rest.model.skill;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface SkillModel<T extends SkillModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

	String getDescription();

	T setDescription(String description);

	String getContent();

	T setContent(String content);

	Boolean getEnabled();

	T setEnabled(Boolean enabled);

	Boolean getPublished();

	T setPublished(Boolean published);

}
