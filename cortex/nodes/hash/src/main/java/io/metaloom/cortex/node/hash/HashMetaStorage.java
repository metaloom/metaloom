package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.XATTR;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.meta.AbstractMetaStorage;
import io.metaloom.cortex.api.meta.MetaStorage;
import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

@Singleton
public class HashMetaStorage extends AbstractMetaStorage {

	public static final LoomMetaKey<ChunkHash> CHUNK_HASH_KEY = metaKey("chunk_hash", 1, XATTR, ChunkHash.class, b -> ChunkHash.fromBuffer(b));

	public static final LoomMetaKey<SHA512> SHA_512_KEY = metaKey("sha512", 1, XATTR, SHA512.class, b -> SHA512.fromBuffer(b));

	public static final LoomMetaKey<SHA256> SHA_256_KEY = metaKey("sha256", 1, XATTR, SHA256.class, b -> SHA256.fromBuffer(b));

	public static final LoomMetaKey<MD5> MD5_KEY = metaKey("md5", 1, XATTR, MD5.class, b -> MD5.fromBuffer(b));

	@Inject
	public HashMetaStorage(MetaStorage storage) {
		super(storage);
	}

	public SHA512 getSHA512(LoomMedia media) {
		return get(media, SHA_512_KEY);
	}

	public void setSHA512(LoomMedia media, SHA512 hash) {
		put(media, SHA_512_KEY, hash);
	}

	public SHA256 getSHA256(LoomMedia media) {
		return get(media, SHA_256_KEY);
	}

	public void setSHA256(LoomMedia media, SHA256 hash) {
		put(media, SHA_256_KEY, hash);
	}

	public MD5 getMD5(LoomMedia media) {
		return get(media, MD5_KEY);
	}

	public void setMD5(LoomMedia media, MD5 hash) {
		put(media, MD5_KEY, hash);
	}

	public ChunkHash getChunkHash(LoomMedia media) {
		return get(media, CHUNK_HASH_KEY);
	}

	public void setChunkHash(LoomMedia media, ChunkHash hash) {
		put(media, CHUNK_HASH_KEY, hash);
	}

}
