package io.metaloom.cortex.node.thumbnail;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.FS;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.XATTR;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.media.param.ThumbnailFlag;
import io.metaloom.cortex.api.meta.AbstractMetaStorage;
import io.metaloom.cortex.api.meta.MetaDataStream;
import io.metaloom.cortex.api.meta.MetaStorage;

@Singleton
public class ThumbnailMetaStorage extends AbstractMetaStorage {

	public static final LoomMetaKey<ThumbnailFlag> THUMBNAIL_FLAG_KEY = metaKey("thumbnail_flags", 1, XATTR, ThumbnailFlag.class);

	public static final LoomMetaKey<MetaDataStream> THUMBNAIL_BIN_KEY = metaKey("thumbnail_bin", 1, FS, MetaDataStream.class);

	@Inject
	public ThumbnailMetaStorage(MetaStorage storage) {
		super(storage);
	}

	public boolean hasThumbnailFlag(LoomMedia media) {
		return has(media, THUMBNAIL_FLAG_KEY);
	}

	public ThumbnailFlag getThumbnailFlag(LoomMedia media) {
		return get(media, THUMBNAIL_FLAG_KEY);
	}

	public void setThumbnailFlag(LoomMedia media, ThumbnailFlag flag) {
		put(media, THUMBNAIL_FLAG_KEY, flag);
	}

	public MetaDataStream getThumbnailBin(LoomMedia media) {
		return get(media, THUMBNAIL_BIN_KEY);
	}

	public void setThumbnailBin(LoomMedia media, MetaDataStream stream) {
		put(media, THUMBNAIL_BIN_KEY, stream);
	}

}
