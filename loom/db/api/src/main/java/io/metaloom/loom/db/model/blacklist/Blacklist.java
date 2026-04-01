package io.metaloom.loom.db.model.blacklist;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.Taggable;

public interface Blacklist extends CUDElement<Blacklist>, Taggable {

	Blacklist setName(String name);

	String getName();

	UUID getAssetUuid();

	Blacklist setAssetUuid(UUID assetUuid);

}
