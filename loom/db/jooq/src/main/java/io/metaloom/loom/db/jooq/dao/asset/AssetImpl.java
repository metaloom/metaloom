package io.metaloom.loom.db.jooq.dao.asset;

import java.time.Instant;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

public class AssetImpl extends AbstractEditableElement<Asset> implements Asset {

	private SHA512 sha512sum;
	private SHA256 sha256sum;
	private MD5 md5sum;
	private ChunkHash chunkHash;
	private long zeroChunkCount;

	// File
	private long size;
	private String filename;
	private String mimeType;
	private Instant firstSeen;
	private String initialOrigin;

	private String s3BucketName;
	private String s3ObjectPath;

	public AssetImpl() {
	}

	@Override
	public SHA512 getSHA512() {
		return sha512sum;
	}

	@Override
	public Asset setSHA512(SHA512 sha512sum) {
		this.sha512sum = sha512sum;
		return this;
	}

	@Override
	public SHA256 getSHA256() {
		return sha256sum;
	}

	@Override
	public Asset setSHA256(SHA256 sha256sum) {
		this.sha256sum = sha256sum;
		return this;
	}

	@Override
	public MD5 getMD5() {
		return md5sum;
	}

	@Override
	public Asset setMD5(MD5 md5sum) {
		this.md5sum = md5sum;
		return this;
	}

	@Override
	public ChunkHash getChunkHash() {
		return chunkHash;
	}

	@Override
	public Asset setChunkHash(ChunkHash chunkHash) {
		this.chunkHash = chunkHash;
		return this;
	}

	@Override
	public long getZeroChunkCount() {
		return zeroChunkCount;
	}

	@Override
	public Asset setZeroChunkCount(long zeroChunkCount) {
		this.zeroChunkCount = zeroChunkCount;
		return this;
	}

	@Override
	public long getSize() {
		return size;
	}

	@Override
	public Asset setSize(long size) {
		this.size = size;
		return this;
	}

	@Override
	public String getFilename() {
		return filename;
	}

	@Override
	public Asset setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	@Override
	public String getS3BucketName() {
		return this.s3BucketName;
	}

	@Override
	public Asset setS3BucketName(String bucketName) {
		this.s3BucketName = bucketName;
		return this;
	}

	@Override
	public String getS3ObjectPath() {
		return s3ObjectPath;
	}

	@Override
	public Asset setS3ObjectPath(String path) {
		this.s3ObjectPath = path;
		return this;
	}

	@Override
	public String getMimeType() {
		return mimeType;
	}

	@Override
	public Asset setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	@Override
	public Instant getFirstSeen() {
		return firstSeen;
	}

	@Override
	public Asset setFirstSeen(Instant time) {
		this.firstSeen = time;
		return this;
	}

	@Override
	public String getInitialOrigin() {
		return initialOrigin;
	}

	@Override
	public Asset setInitialOrigin(String initialOrigin) {
		this.initialOrigin = initialOrigin;
		return this;
	}

}
