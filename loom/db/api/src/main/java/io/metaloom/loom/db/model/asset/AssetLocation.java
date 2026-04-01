package io.metaloom.loom.db.model.asset;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;
import io.metaloom.loom.db.Taggable;

public interface AssetLocation extends CUDElement<AssetLocation>, Taggable, MetaElement<AssetLocation> {

	/**
	 * Return the filesystem path of the asset.
	 * 
	 * @return
	 */
	String getPath();

	/**
	 * Set the filesystem path of the asset.
	 * 
	 * @param filename
	 * @return Fluent API
	 */
	AssetLocation setPath(String filename);

	UUID getAssetUuid();

	AssetLocation setAssetUuid(UUID assetUuid);

	UUID getLibraryUuid();

	AssetLocation setLibraryUuid(UUID libraryUuid);

	String getMimeType();

	AssetLocation setMimeType(String mimeType);

	Long getFilekeyInode();

	AssetLocation setFilekeyInode(Long inode);

	Long getFilekeyStDev();

	AssetLocation setFilekeyStDev(Long stDev);

	Long getFilekeyEdate();

	AssetLocation setFilekeyEdate(Long edate);

	Long getFilekeyEdateNano();

	AssetLocation setFilekeyEdateNano(Long edate);

}
