package io.metaloom.loom.api.asset;

import java.util.UUID;

import io.metaloom.loom.api.asset.impl.AssetIdImpl;
import io.metaloom.utils.UUIDUtils;
import io.metaloom.utils.hash.SHA512;

public interface AssetId {

	boolean isUUID();

	SHA512 hashsum();

	UUID uuid();

	public static AssetId assetId(UUID uuid) {
		return new AssetIdImpl(uuid);
	}

	public static AssetId assetId(SHA512 hash) {
		return new AssetIdImpl(hash);
	}

	public static AssetId assetId(String id) {
		if (UUIDUtils.isUUID(id)) {
			return new AssetIdImpl(UUIDUtils.fromString(id));
		} else {
			SHA512 sha512 = SHA512.fromString(id);
			return new AssetIdImpl(sha512);
		}
	}
}
