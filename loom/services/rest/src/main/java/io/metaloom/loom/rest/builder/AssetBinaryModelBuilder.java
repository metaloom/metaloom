package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.asset.binary.FileKey;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryFilesystemInfo;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryListResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryReference;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryResponse;

public interface AssetBinaryModelBuilder extends ModelBuilder, UserModelBuilder {

	default AssetBinaryResponse toResponse(AssetBinary location) {
		AssetBinaryResponse model = new AssetBinaryResponse();
		model.setUuid(location.getUuid());
		model.setMeta(location.getMeta());
		model.setLibraryUuid(location.getLibraryUuid());
		model.setAssetUuid(location.getAssetUuid());
		model.setFilesystem(filesystemLocationInfo(location));
		setStatus(location, model);
		return model;
	}

	default AssetBinaryFilesystemInfo filesystemLocationInfo(AssetBinary location) {
		AssetBinaryFilesystemInfo model = new AssetBinaryFilesystemInfo();
		// location.setLastSeen(asset.getLastSeen());
		model.setPath(location.getPath());
		model.setFilekey(assetFilekey(location));
		return model;
	}

	default AssetBinaryReference toReference(AssetBinary location) {
		AssetBinaryReference model = new AssetBinaryReference();
		model.setPath(location.getPath());
		model.setUuid(location.getUuid());
		return model;
	}

	default FileKey assetFilekey(AssetBinary asset) {
		FileKey key = new FileKey();
		key.setInode(asset.getFilekeyInode());
		key.setStDev(asset.getFilekeyStDev());
		key.setEDate(asset.getFilekeyEdate());
		key.setEDateNano(asset.getFilekeyEdateNano());
		return key;
	}

	default AssetBinaryListResponse toBinaryList(Page<AssetBinary> page) {
		return setPage(new AssetBinaryListResponse(), page, this::toResponse);
	}

}
