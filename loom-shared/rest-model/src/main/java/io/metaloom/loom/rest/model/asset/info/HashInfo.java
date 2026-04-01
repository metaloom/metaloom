package io.metaloom.loom.rest.model.asset.info;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

public class HashInfo implements RestModel {

	@JsonPropertyDescription("SHA512 checksum of the asset.")
	private SHA512 sha512;

	@JsonPropertyDescription("SHA256 checksum of the asset.")
	private SHA256 sha256;

	@JsonPropertyDescription("MD5 checksum of the asset.")
	private MD5 md5;

	@JsonPropertyDescription("Chunk hash checksum of the asset.")
	private ChunkHash chunkHash;

	public HashInfo() {
	}

	public SHA512 getSHA512() {
		return sha512;
	}

	public HashInfo setSHA512(SHA512 sha512) {
		this.sha512 = sha512;
		return this;
	}

	public SHA256 getSHA256() {
		return sha256;
	}

	public HashInfo setSHA256(SHA256 sha256) {
		this.sha256 = sha256;
		return this;
	}

	public MD5 getMD5() {
		return md5;
	}

	public HashInfo setMD5(MD5 md5) {
		this.md5 = md5;
		return this;
	}

	public ChunkHash getChunkHash() {
		return chunkHash;
	}

	public HashInfo setChunkHash(ChunkHash chunkHash) {
		this.chunkHash = chunkHash;
		return this;
	}

}
