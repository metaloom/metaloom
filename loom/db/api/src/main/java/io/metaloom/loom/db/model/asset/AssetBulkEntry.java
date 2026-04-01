package io.metaloom.loom.db.model.asset;

import io.metaloom.utils.hash.SHA512;

/**
 * Simple bulk entry containing the mandatory fields for creating an asset.
 */
public class AssetBulkEntry {

	private final SHA512 sha512;
	private final String mimeType;
	private final String filename;
	private final String initialOrigin;
	private final long size;

	public AssetBulkEntry(SHA512 sha512, String mimeType, String filename, String initialOrigin, long size) {
		this.sha512 = sha512;
		this.mimeType = mimeType;
		this.filename = filename;
		this.initialOrigin = initialOrigin;
		this.size = size;
	}

	public SHA512 sha512() {
		return sha512;
	}

	public String mimeType() {
		return mimeType;
	}

	public String filename() {
		return filename;
	}

	public String initialOrigin() {
		return initialOrigin;
	}

	public long size() {
		return size;
	}

}
