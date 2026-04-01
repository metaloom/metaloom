package io.metaloom.loom.rest.model.library;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface LibraryModel<T extends LibraryModel<T>> extends MetaModel<T>, RestModel {

	String getName();

	T setName(String name);

}
