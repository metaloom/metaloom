package io.metaloom.loom.db.jooq.dao.project;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.project.Project;

public class ProjectImpl extends AbstractEditableElement<Project> implements Project {

	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Project setName(String name) {
		this.name = name;
		return this;
	}

}
