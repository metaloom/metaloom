package io.metaloom.cortex.node.tika;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.XATTR;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.meta.AbstractMetaStorage;
import io.metaloom.cortex.api.meta.MetaStorage;

@Singleton
public class TikaMetaStorage extends AbstractMetaStorage {

	public static final LoomMetaKey<String> TIKA_FLAGS_KEY = metaKey("tika_flags", 1, XATTR, String.class);

	@Inject
	public TikaMetaStorage(MetaStorage storage) {
		super(storage);
	}

	public boolean hasTikaFlags(LoomMedia media) {
		return has(media, TIKA_FLAGS_KEY);
	}

	public String getTikaFlags(LoomMedia media) {
		return get(media, TIKA_FLAGS_KEY);
	}

	public void setTikaFlags(LoomMedia media, String flags) {
		put(media, TIKA_FLAGS_KEY, flags);
	}

}
