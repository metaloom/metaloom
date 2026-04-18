package io.metaloom.cortex.node.consistency;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.XATTR;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.meta.AbstractMetaStorage;
import io.metaloom.cortex.api.meta.MetaStorage;

@Singleton
public class ConsistencyMetaStorage extends AbstractMetaStorage {

	public static final LoomMetaKey<Long> ZERO_CHUNK_COUNT_KEY = metaKey("zero_chunk_count", 1, XATTR, Long.class);

	@Inject
	public ConsistencyMetaStorage(MetaStorage storage) {
		super(storage);
	}

	public boolean hasZeroChunkCount(LoomMedia media) {
		return has(media, ZERO_CHUNK_COUNT_KEY);
	}

	public Long getZeroChunkCount(LoomMedia media) {
		return get(media, ZERO_CHUNK_COUNT_KEY);
	}

	public void setZeroChunkCount(LoomMedia media, Long count) {
		put(media, ZERO_CHUNK_COUNT_KEY, count);
	}

}
