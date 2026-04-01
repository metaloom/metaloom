package io.metaloom.loom.rest.model.common;

import java.util.UUID;

import io.metaloom.loom.rest.model.RestResponseModel;

public abstract class AbstractResponse<T extends AbstractResponse<T>> implements RestResponseModel<T> {

	private UUID uuid;

	public UUID getUuid() {
		return uuid;
	}

	public T setUuid(UUID uuid) {
		this.uuid = uuid;
		return self();
	}

}
