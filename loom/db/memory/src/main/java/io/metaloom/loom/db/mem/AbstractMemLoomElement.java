package io.metaloom.loom.db.mem;

import java.util.UUID;

import io.metaloom.loom.db.Element;

public abstract class AbstractMemLoomElement<SELF extends Element<SELF>> implements Element<SELF> {

	private UUID uuid;

	@Override
	public UUID getUuid() {
		return uuid;
	}

	@Override
	public SELF setUuid(UUID uuid) {
		this.uuid = uuid;
		return self();
	}

}
