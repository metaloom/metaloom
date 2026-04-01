package io.metaloom.loom.db.jooq.dao.collection;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.collection.Collection;

public class CollectionImpl extends AbstractEditableElement<Collection> implements Collection {

	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Collection setName(String name) {
		this.name = name;
		return this;
	}

}
