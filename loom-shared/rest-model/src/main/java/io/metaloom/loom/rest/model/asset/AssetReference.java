package io.metaloom.loom.rest.model.asset;

import java.util.UUID;

import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.utils.hash.SHA512;

public class AssetReference implements RestModel {

	private SHA512 sha512sum;

	private UUID uuid;

	public UUID getUuid() {
		return uuid;
	}

	public AssetReference setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public SHA512 getSha512sum() {
		return sha512sum;
	}

	public AssetReference setSha512sum(SHA512 sha512sum) {
		this.sha512sum = sha512sum;
		return this;
	}
}
