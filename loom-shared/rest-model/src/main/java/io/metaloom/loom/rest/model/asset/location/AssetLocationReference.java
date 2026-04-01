package io.metaloom.loom.rest.model.asset.location;

import java.util.UUID;

import io.metaloom.loom.rest.model.RestModel;

public class AssetLocationReference implements RestModel {

	private UUID uuid;

	private String path;

	public AssetLocationReference() {
	}

	public UUID getUuid() {
		return uuid;
	}

	public AssetLocationReference setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public AssetLocationReference setPath(String path) {
		this.path = path;
		return this;
	}

	public String getPath() {
		return path;
	}

}
