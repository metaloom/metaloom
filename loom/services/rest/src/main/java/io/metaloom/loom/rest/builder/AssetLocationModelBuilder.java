package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.asset.location.FileKey;
import io.metaloom.loom.rest.model.asset.location.AssetLocationFilesystemInfo;
import io.metaloom.loom.rest.model.asset.location.AssetLocationListResponse;
import io.metaloom.loom.rest.model.asset.location.AssetLocationReference;
import io.metaloom.loom.rest.model.asset.location.AssetLocationResponse;

/**
 * The {@code AssetLocationResponse} view of a row in {@code asset_location}.
 *
 * <p>
 * {@link AssetBinaryModelBuilder} builds the other view of the same row for the {@code /assets/:uuid/binaries} routes, which is why every method here
 * carries "location" in its name: both interfaces are inherited by {@link LoomModelBuilder}, so the two views cannot both be called {@code
 * toResponse}. The response models genuinely differ - this one carries state, license and lock, the binary one carries the storage-type
 * discriminator and the S3 split.
 * </p>
 */
public interface AssetLocationModelBuilder extends ModelBuilder, UserModelBuilder {

	default AssetLocationResponse toLocationResponse(AssetBinary location) {
		AssetLocationResponse model = new AssetLocationResponse();
		model.setUuid(location.getUuid());
		model.setMeta(location.getMeta());
		model.setLibraryUuid(location.getLibraryUuid());
		model.setAssetUuid(location.getAssetUuid());
		model.setMimeType(location.getMimeType());
		model.setPoolUuid(location.getPoolUuid());
		model.setState(location.getState());
		model.setLicense(location.getLicense());
		model.setLockedByUuid(location.getLockedByUuid());
		model.setFilesystem(locationFilesystemInfo(location));
		setStatus(location, model);
		return model;
	}

	default AssetLocationFilesystemInfo locationFilesystemInfo(AssetBinary location) {
		AssetLocationFilesystemInfo model = new AssetLocationFilesystemInfo();
		// location.setLastSeen(asset.getLastSeen());
		model.setPath(location.getPath());
		model.setFilekey(locationFilekey(location));
		return model;
	}

	default AssetLocationReference toLocationReference(AssetBinary location) {
		AssetLocationReference model = new AssetLocationReference();
		model.setPath(location.getPath());
		model.setUuid(location.getUuid());
		return model;
	}

	default FileKey locationFilekey(AssetBinary asset) {
		FileKey key = new FileKey();
		key.setInode(asset.getFilekeyInode());
		key.setStDev(asset.getFilekeyStDev());
		key.setEDate(asset.getFilekeyEdate());
		key.setEDateNano(asset.getFilekeyEdateNano());
		return key;
	}

	default AssetLocationListResponse toLocationList(Page<AssetBinary> page) {
		return setPage(new AssetLocationListResponse(), page, this::toLocationResponse);
	}

}
