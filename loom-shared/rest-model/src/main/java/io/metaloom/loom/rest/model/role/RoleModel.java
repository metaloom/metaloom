package io.metaloom.loom.rest.model.role;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface RoleModel<T extends RoleModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

}
