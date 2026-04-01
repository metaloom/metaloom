package io.metaloom.loom.rest.model.collection;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface CollectionModel<T extends CollectionModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

}
