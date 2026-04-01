package io.metaloom.loom.db.jooq.dao.library;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.library.Library;

public class LibraryImpl extends AbstractEditableElement<Library> implements Library {

	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Library setName(String name) {
		this.name = name;
		return this;
	}

}
