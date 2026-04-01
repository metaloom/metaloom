package io.metaloom.loom.rest.model.project;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface ProjectModel<T extends ProjectModel<T>> extends RestModel, MetaModel<T> {

	String getName();

	T setName(String name);

}
