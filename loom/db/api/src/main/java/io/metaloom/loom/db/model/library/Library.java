package io.metaloom.loom.db.model.library;

import io.metaloom.loom.db.CUDElement;

public interface Library extends CUDElement<Library> {

	String getName();

	Library setName(String name);

}
