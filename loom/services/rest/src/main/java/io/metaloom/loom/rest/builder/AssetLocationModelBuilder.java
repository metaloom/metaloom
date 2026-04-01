package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.asset.AssetLocation;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.asset.location.FileKey;
import io.metaloom.loom.rest.model.asset.location.AssetLocationFilesystemInfo;
import io.metaloom.loom.rest.model.asset.location.AssetLocationListResponse;
import io.metaloom.loom.rest.model.asset.location.AssetLocationReference;
import io.metaloom.loom.rest.model.asset.location.AssetLocationResponse;

public interface AssetLocationModelBuilder extends ModelBuilder, UserModelBuilder {

	default AssetLocationResponse toResponse(AssetLocation location) {
		AssetLocationResponse model = new AssetLocationResponse();
		model.setUuid(location.getUuid());
		model.setMeta(location.getMeta());
		model.setLibraryUuid(location.getLibraryUuid());
		model.setAssetUuid(location.getAssetUuid());
		model.setFilesystem(filesystemLocationInfo(location));
		setStatus(location, model);
		return model;
	}

	default AssetLocationFilesystemInfo filesystemLocationInfo(AssetLocation location) {
		AssetLocationFilesystemInfo model = new AssetLocationFilesystemInfo();
		// location.setLastSeen(asset.getLastSeen());
		model.setPath(location.getPath());
		model.setFilekey(assetFilekey(location));
		return model;
	}

	default AssetLocationReference toReference(AssetLocation location) {
		AssetLocationReference model = new AssetLocationReference();
		model.setPath(location.getPath());
		model.setUuid(location.getUuid());
		return model;
	}

	default FileKey assetFilekey(AssetLocation asset) {
		FileKey key = new FileKey();
		key.setInode(asset.getFilekeyInode());
		key.setStDev(asset.getFilekeyStDev());
		key.setEDate(asset.getFilekeyEdate());
		key.setEDateNano(asset.getFilekeyEdateNano());
		return key;
	}

	default AssetLocationListResponse toLocationList(Page<AssetLocation> page) {
		return setPage(new AssetLocationListResponse(), page, this::toResponse);
	}

}
