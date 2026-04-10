package io.metaloom.loom.db.model.asset;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;
import io.metaloom.loom.db.Taggable;

public interface AssetBinary extends CUDElement<AssetBinary>, Taggable, MetaElement<AssetBinary> {

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
	AssetBinary setPath(String filename);

	UUID getAssetUuid();

	AssetBinary setAssetUuid(UUID assetUuid);

	UUID getLibraryUuid();

	AssetBinary setLibraryUuid(UUID libraryUuid);

	String getMimeType();

	AssetBinary setMimeType(String mimeType);

	Long getFilekeyInode();

	AssetBinary setFilekeyInode(Long inode);

	Long getFilekeyStDev();

	AssetBinary setFilekeyStDev(Long stDev);

	Long getFilekeyEdate();

	AssetBinary setFilekeyEdate(Long edate);

	Long getFilekeyEdateNano();

	AssetBinary setFilekeyEdateNano(Long edate);

}
