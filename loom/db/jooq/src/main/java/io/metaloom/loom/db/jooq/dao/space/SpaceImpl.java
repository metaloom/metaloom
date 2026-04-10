package io.metaloom.loom.db.jooq.dao.space;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.space.Space;

public class SpaceImpl extends AbstractEditableElement<Space> implements Space {

	private String name;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Space setName(String name) {
		this.name = name;
		return this;
	}

}
