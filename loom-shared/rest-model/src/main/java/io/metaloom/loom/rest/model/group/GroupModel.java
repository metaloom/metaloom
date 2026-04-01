package io.metaloom.loom.rest.model.group;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface GroupModel<T extends GroupModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

}
