package io.metaloom.loom.db.model.space;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

public interface Space extends CUDElement<Space>, MetaElement<Space> {

	String getName();

	Space setName(String name);

}
