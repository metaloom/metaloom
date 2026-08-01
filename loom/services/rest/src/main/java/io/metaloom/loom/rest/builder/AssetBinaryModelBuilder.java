package io.metaloom.loom.rest.builder;

import java.util.List;

import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryFilesystemInfo;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryListResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryReference;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetS3Meta;
import io.metaloom.loom.rest.model.asset.binary.FileKey;

public interface AssetBinaryModelBuilder extends ModelBuilder, UserModelBuilder {

	/** Locator scheme for S3-backed binaries. Kept in sync with {@code io.metaloom.loom.storage.s3.S3Locator}. */
	String S3_SCHEME = "s3://";

	String STORAGE_TYPE_S3 = "s3";

	String STORAGE_TYPE_FILESYSTEM = "filesystem";

	/**
	 * Build the response for a binary.
	 *
	 * <p>
	 * Exactly one of {@code filesystem} and {@code s3} is populated, decided by the locator itself rather than by the pool. Reading it from the
	 * locator means a row still describes itself correctly after its pool has been edited or deleted — and it is what finally makes the {@code s3}
	 * field of this model, present in the API since it was written, ever appear in a response.
	 * </p>
	 */
	default AssetBinaryResponse toResponse(AssetBinary location) {
		AssetBinaryResponse model = new AssetBinaryResponse();
		model.setUuid(location.getUuid());
		model.setMeta(location.getMeta());
		model.setLibraryUuid(location.getLibraryUuid());
		model.setAssetUuid(location.getAssetUuid());
		model.setPoolUuid(location.getPoolUuid());

		String path = location.getPath();
		if (path != null && path.startsWith(S3_SCHEME)) {
			model.setStorageType(STORAGE_TYPE_S3);
			model.setS3(s3Info(path));
		} else {
			model.setStorageType(STORAGE_TYPE_FILESYSTEM);
			model.setFilesystem(filesystemLocationInfo(location));
		}
		setStatus(location, model);
		return model;
	}

	/**
	 * Split {@code s3://bucket/key} back into its parts for {@link AssetS3Meta}.
	 */
	default AssetS3Meta s3Info(String locator) {
		AssetS3Meta model = new AssetS3Meta();
		String remainder = locator.substring(S3_SCHEME.length());
		int slash = remainder.indexOf('/');
		if (slash < 0) {
			model.setBucket(remainder);
		} else {
			model.setBucket(remainder.substring(0, slash));
			model.setObjectPath(remainder.substring(slash + 1));
		}
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

	/**
	 * An unpaged list, for the sub-resource route that returns every binary of one asset. An asset has one location per library it was imported
	 * into, so this is bounded by the library count and does not need paging.
	 */
	default AssetBinaryListResponse toBinaryList(List<AssetBinary> binaries) {
		AssetBinaryListResponse response = new AssetBinaryListResponse();
		for (AssetBinary binary : binaries) {
			response.add(toResponse(binary));
		}
		PagingInfo metainfo = new PagingInfo();
		metainfo.setTotalCount((long) binaries.size());
		metainfo.setPerPage((long) binaries.size());
		response.setMetainfo(metainfo);
		return response;
	}

}
