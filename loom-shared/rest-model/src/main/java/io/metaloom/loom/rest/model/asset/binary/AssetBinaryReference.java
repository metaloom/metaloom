package io.metaloom.loom.rest.model.asset.binary;

import java.util.UUID;

import io.metaloom.loom.rest.model.RestModel;

public class AssetBinaryReference implements RestModel {

	private UUID uuid;

	private String path;

	public AssetBinaryReference() {
	}

	public UUID getUuid() {
		return uuid;
	}

	public AssetBinaryReference setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public AssetBinaryReference setPath(String path) {
		this.path = path;
		return this;
	}

	public String getPath() {
		return path;
	}

}
