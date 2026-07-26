package io.metaloom.cortex.node.facedetect;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Paths;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.StringKey;
import io.metaloom.cortex.api.node.CortexNode;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.dlib.DLibModelProvisioner;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;
import io.metaloom.facedetection.client.FaceDetectionServerClient;
import io.metaloom.video.facedetect.dlib.DLibFacedetector;
import io.metaloom.video.facedetect.dlib.impl.DLibFacedetectorImpl;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;

@Module
public abstract class FacedetectNodeModule extends AbstractNodeModule {

	public static final Logger log = LoggerFactory.getLogger(FacedetectNodeModule.class);

	public static final String FACE_DETECT_SERVER_BASEURL = "http://cortex:8010/api/v1";

	@Binds
	@IntoSet
	abstract CortexNode<?, ?> bindNode(FacedetectNode action);

	@Binds
	@IntoMap
	@StringKey("facedetect")
	abstract FilesystemNode<?, ?> kindFacedetect(FacedetectNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(FacedetectNodeOptions.class, FacedetectNodeOptions.KEY);
	}

	@Provides
	public static FacedetectNodeOptions options(CortexOptions options) {
		return nodeOptions(options, FacedetectNodeOptions.KEY, new FacedetectNodeOptions());
	}

	@Provides
	public static FaceDetectionServerClient detectionClient(FacedetectNodeOptions options) {
		FaceDetectionServerClient client = FaceDetectionServerClient.newBuilder()
			.setBaseURL(FACE_DETECT_SERVER_BASEURL).build();
		return client;

	}

	@Provides
	public static DLibFacedetector dlibDetector(FacedetectNodeOptions options) {
		if (!options.getCapabilities().contains(FacedetectNodeCapabilities.DLIB)) {
			return null;
		}
		try {
			DLibModelProvisioner.extractModelData(Paths.get("dlib"));
			// DLIB_DETECTOR = DLibFacedetector.create();
			// DLIB_DETECTOR.setMinFaceHeightFactor(0.01f);
			// DLIB_DETECTOR.enableCNNDetector();
			return new DLibFacedetectorImpl();
		} catch (IOException e) {
			throw new RuntimeException("Failed to extract dlib models", e);
		}
	}

	@Provides
	public static InspireFacedetector inspirefaceDetector(FacedetectNodeOptions options) {
		if (!options.getCapabilities().contains(FacedetectNodeCapabilities.INSPIREFACE)) {
			return null;
		}
		try {
			String packPath = options.getInspirefacePackPath();
			InspireFacedetector detector = InspireFacedetector.create(packPath, 640, true, true, true);
			detector.setMinFaceHeightFactor(options.getMinFaceHeightFactor());
			return detector;
		} catch (FileNotFoundException e) {
			log.error("Failed to load dlib", e);
			throw new RuntimeException(e);
		}
	}

}
