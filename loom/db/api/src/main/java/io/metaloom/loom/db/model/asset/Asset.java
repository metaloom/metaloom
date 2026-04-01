package io.metaloom.loom.db.model.asset;

import java.time.Instant;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.Taggable;

public interface Asset
	extends CUDElement<Asset>, Taggable, AssetHashInfo {

	long getZeroChunkCount();

	Asset setZeroChunkCount(long zeroChunkCount);

	/**
	 * Return the asset size in bytes.
	 * 
	 * @return
	 */
	long getSize();

	/**
	 * Set the asset size in bytes.
	 * 
	 * @param size
	 * @return Fluent API
	 */
	Asset setSize(long size);

	String getFilename();

	Asset setFilename(String filename);

	String getMimeType();

	Asset setMimeType(String mimeType);

	String getS3BucketName();

	Asset setS3BucketName(String bucketName);

	String getS3ObjectPath();

	Asset setS3ObjectPath(String path);

	String getInitialOrigin();

	Asset setInitialOrigin(String initialOrigin);

	Instant getFirstSeen();

	Asset setFirstSeen(Instant time);

}
