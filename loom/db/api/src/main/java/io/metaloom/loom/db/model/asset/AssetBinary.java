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

	/**
	 * The storage pool holding these bytes.
	 *
	 * <p>
	 * The {@code asset_location.pool_uuid} column has existed since {@code V2.20} and, until pools were wired into the upload path, nothing ever
	 * wrote it. NULL still means "the process-wide local upload directory", which is what every pre-pool row holds and what a library without a pool
	 * keeps producing.
	 * </p>
	 *
	 * @return the pool uuid, or null for the default local storage
	 */
	UUID getPoolUuid();

	AssetBinary setPoolUuid(UUID poolUuid);

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
