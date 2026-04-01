package io.metaloom.loom.db.model.asset;

import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

public interface AssetHashInfo {

	SHA512 getSHA512();

	Asset setSHA512(SHA512 hashsum);

	SHA256 getSHA256();

	Asset setSHA256(SHA256 hashsum);

	MD5 getMD5();

	Asset setMD5(MD5 hashsum);

	ChunkHash getChunkHash();

	Asset setChunkHash(ChunkHash chunkHash);

}
