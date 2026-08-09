package io.metaloom.loom.db.jooq.dao.asset.binary;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.AssetBinary;

public class AssetBinaryImpl extends AbstractEditableElement<AssetBinary> implements AssetBinary {

	private String path;

	private UUID assetUuid;

	private UUID libraryUuid;

	private UUID poolUuid;

	private String mimeType;

	private String state;

	private String license;

	private UUID lockedByUuid;

	private Long filekeyInode;

	// Spelled the way jOOQ camel-cases the column: "filekey_stdev" -> "filekeyStdev". The field used
	// to be "filekeyStDev", which matched nothing, so the column was silently dropped on write and
	// read back as the primitive default - the file key the REST layer hands out always carried
	// stDev 0. Boxed for the same reason getFilekeyInode is: the column is nullable.
	private Long filekeyStdev;

	private Long filekeyEdate;

	private Long filekeyEdateNano;

	@Override
	public String getPath() {
		return path;
	}

	@Override
	public AssetBinary setPath(String path) {
		this.path = path;
		return this;
	}

	@Override
	public UUID getLibraryUuid() {
		return libraryUuid;
	}

	@Override
	public AssetBinary setLibraryUuid(UUID libraryUuid) {
		this.libraryUuid = libraryUuid;
		return this;
	}

	@Override
	public UUID getPoolUuid() {
		return poolUuid;
	}

	@Override
	public AssetBinary setPoolUuid(UUID poolUuid) {
		this.poolUuid = poolUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AssetBinary setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getMimeType() {
		return mimeType;
	}

	@Override
	public AssetBinary setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	@Override
	public String getState() {
		return state;
	}

	@Override
	public AssetBinary setState(String state) {
		this.state = state;
		return this;
	}

	@Override
	public String getLicense() {
		return license;
	}

	@Override
	public AssetBinary setLicense(String license) {
		this.license = license;
		return this;
	}

	@Override
	public UUID getLockedByUuid() {
		return lockedByUuid;
	}

	@Override
	public AssetBinary setLockedByUuid(UUID lockedByUuid) {
		this.lockedByUuid = lockedByUuid;
		return this;
	}

	@Override
	public Long getFilekeyInode() {
		return filekeyInode;
	}

	@Override
	public AssetBinary setFilekeyInode(Long inode) {
		this.filekeyInode = inode;
		return this;
	}

	@Override
	public Long getFilekeyStDev() {
		return filekeyStdev;
	}

	@Override
	public AssetBinary setFilekeyStDev(Long stDev) {
		this.filekeyStdev = stDev;
		return this;
	}

	@Override
	public Long getFilekeyEdate() {
		return filekeyEdate;
	}

	@Override
	public AssetBinary setFilekeyEdate(Long edate) {
		this.filekeyEdate = edate;
		return this;
	}

	@Override
	public Long getFilekeyEdateNano() {
		return filekeyEdateNano;
	}

	@Override
	public AssetBinary setFilekeyEdateNano(Long edate) {
		this.filekeyEdateNano = edate;
		return this;
	}

	@Override
	public String toString() {
		return String.format("Asset [%s] - %s", getUuid(), getPath());
	}

}
