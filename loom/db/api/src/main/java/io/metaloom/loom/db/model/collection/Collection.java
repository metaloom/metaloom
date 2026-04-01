package io.metaloom.loom.db.model.collection;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;
import io.metaloom.loom.db.Taggable;

public interface Collection extends CUDElement<Collection>, Taggable, MetaElement<Collection> {

	String getName();

	Collection setName(String name);

}
