package io.metaloom.loom.db.model.project;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

public interface Project extends CUDElement<Project>, MetaElement<Project> {

	String getName();

	Project setName(String name);

}
