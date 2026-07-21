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

	/**
	 * Return the storage pool in which the binary of this location is stored.
	 *
	 * @return
	 */
	UUID getPoolUuid();

	AssetLocation setPoolUuid(UUID poolUuid);

	/**
	 * Return the current state of the location (e.g. whether the binary is present, missing, ...).
	 *
	 * @return
	 */
	String getState();

	AssetLocation setState(String state);

	/**
	 * Return the license which applies to the binary at this location.
	 *
	 * @return
	 */
	String getLicense();

	AssetLocation setLicense(String license);

	/**
	 * Return the uuid of the user which currently holds a lock on this location.
	 *
	 * @return
	 */
	UUID getLockedByUuid();

	AssetLocation setLockedByUuid(UUID lockedByUuid);

	Long getFilekeyInode();

	AssetLocation setFilekeyInode(Long inode);

	Long getFilekeyStDev();

	AssetLocation setFilekeyStDev(Long stDev);

	Long getFilekeyEdate();

	AssetLocation setFilekeyEdate(Long edate);

	Long getFilekeyEdateNano();

	AssetLocation setFilekeyEdateNano(Long edate);

}
