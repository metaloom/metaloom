package io.metaloom.loom.rest.model.space;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface SpaceModel<T extends SpaceModel<T>> extends RestModel, MetaModel<T> {

	String getName();

	T setName(String name);

}
