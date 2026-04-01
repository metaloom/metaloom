package io.metaloom.loom.db.jooq.dao.tag;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.tag.Tag;

/**
 * @See {@link Tag}
 */
public class TagImpl extends AbstractEditableElement<Tag> implements Tag {

	private String name;
	private String collectionName;
	private String color;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Tag setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public Tag setCollection(String collectionName) {
		this.collectionName = collectionName;
		return this;
	}

	@Override
	public String getColor() {
		return color;
	}

	@Override
	public Tag setColor(String color) {
		this.color = color;
		return this;
	}

	@Override
	public String getCollection() {
		return collectionName;
	}

}
