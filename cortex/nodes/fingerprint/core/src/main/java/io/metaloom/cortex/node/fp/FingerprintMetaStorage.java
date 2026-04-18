package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.XATTR;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.meta.AbstractMetaStorage;
import io.metaloom.cortex.api.meta.MetaStorage;

@Singleton
public class FingerprintMetaStorage extends AbstractMetaStorage {

	public static final LoomMetaKey<String> FINGERPRINT_KEY = metaKey("fingerprint", 1, XATTR, String.class);

	@Inject
	public FingerprintMetaStorage(MetaStorage storage) {
		super(storage);
	}

	public boolean hasFingerprint(LoomMedia media) {
		return has(media, FINGERPRINT_KEY);
	}

	public String getFingerprint(LoomMedia media) {
		return get(media, FINGERPRINT_KEY);
	}

	public void setFingerprint(LoomMedia media, String fingerprint) {
		put(media, FINGERPRINT_KEY, fingerprint);
	}

}
