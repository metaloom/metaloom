package io.metaloom.cortex.api.meta;

import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.utils.hash.SHA512;

public abstract class AbstractMetaStorage implements MetaStorage {

	private MetaStorage storage;

	public AbstractMetaStorage(MetaStorage storage) {
		this.storage = storage;
	}

	@Override
	public <T> boolean has(LoomMedia media, LoomMetaKey<T> metaKey) {
		return storage.has(media, metaKey);
	}

	@Override
	public <T> T get(LoomMedia media, LoomMetaKey<T> metaKey) {
		return storage.get(media, metaKey);
	}

	@Override
	public <T> List<T> getAll(LoomMedia media, LoomMetaKey<T> metaKey) {
		return storage.getAll(media, metaKey);
	}

	@Override
	public <T> void put(LoomMedia media, LoomMetaKey<T> metaKey, T value) {
		storage.put(media, metaKey, value);
	}

	@Override
	public <T> void append(LoomMedia media, LoomMetaKey<T> metakey, T value) {
		storage.append(media, metakey, value);

	}

	@Override
	public void setSHA512(LoomMedia media, SHA512 hash) {
		storage.setSHA512(media, hash);

	}

	@Override
	public SHA512 getSHA512(LoomMedia media) {
		return storage.getSHA512(media);
	}

}
