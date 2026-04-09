package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.AVRO;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.XATTR;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.media.flag.FaceDetectionFlag;
import io.metaloom.cortex.api.meta.AbstractMetaStorage;
import io.metaloom.cortex.api.meta.MetaStorage;
import io.metaloom.loom.cortex.node.facedetect.avro.Facedetection;

@Singleton
public class FacedetectionMetaStorage extends AbstractMetaStorage {

	/**
	 * XAttr which stores the count of detected faces.
	 */
	public static final LoomMetaKey<Integer> FACEDETECT_COUNT_KEY = metaKey("facedetect_count", 1, XATTR, Integer.class);

	/**
	 * XAttr which stores the state of the fact detection process.
	 */
	public static final LoomMetaKey<FaceDetectionFlag> FACEDETECTION_FLAG_KEY = metaKey("facedetect_flag", 1, XATTR, FaceDetectionFlag.class);

	public static final LoomMetaKey<Facedetection> FACEDETECTION_RESULT_KEY = metaKey("facedetect_result", 1, AVRO, Facedetection.class);

	@Inject
	public FacedetectionMetaStorage(MetaStorage storage) {
		super(storage);
	}

	public Integer getFaceCount(LoomMedia media) {
		return get(media, FACEDETECT_COUNT_KEY);
	}

	public void setFaceCount(LoomMedia media, Integer count) {
		put(media, FACEDETECT_COUNT_KEY, count);
	}

	public FaceDetectionFlag getFacedetectionFlag(LoomMedia media) {
		return get(media, FACEDETECTION_FLAG_KEY);
	}

	public List<Facedetection> getFacedetections(LoomMedia media) {
		return getAll(media, FACEDETECTION_RESULT_KEY);
	}

	public void setFacedetectionFlag(LoomMedia media, FaceDetectionFlag flag) {
		put(media, FACEDETECTION_FLAG_KEY, flag);
	}

	public void appendFacedetection(LoomMedia media, Facedetection result) {
		append(media, FACEDETECTION_RESULT_KEY, result);
	}

	public boolean hasFacedetectionFlag(LoomMedia media) {
		return getFacedetectionFlag(media) != null;
	}

}
