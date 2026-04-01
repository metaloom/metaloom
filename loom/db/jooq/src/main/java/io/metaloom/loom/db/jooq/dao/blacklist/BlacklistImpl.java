package io.metaloom.loom.db.jooq.dao.blacklist;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.blacklist.Blacklist;

public class BlacklistImpl extends AbstractEditableElement<Blacklist> implements Blacklist {

	private String name;
	private UUID assetUuid;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Blacklist setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Blacklist setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

}
