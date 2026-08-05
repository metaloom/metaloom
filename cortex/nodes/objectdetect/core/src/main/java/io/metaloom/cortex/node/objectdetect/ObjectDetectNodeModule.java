package io.metaloom.cortex.node.objectdetect;

import javax.inject.Singleton;

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
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

@Module
public abstract class ObjectDetectNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract CortexNode<?, ?> bindNode(ObjectDetectNode node);

	@Binds
	@IntoMap
	@StringKey("objectdetect")
	abstract FilesystemNode<?, ?> kindObjectDetect(ObjectDetectNode node);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(ObjectDetectNodeOptions.class, ObjectDetectNodeOptions.KEY);
	}

	@Provides
	public static ObjectDetectNodeOptions options(CortexOptions options) {
		return nodeOptions(options, ObjectDetectNodeOptions.KEY, new ObjectDetectNodeOptions());
	}

	/**
	 * The detection engine, one per worker.
	 *
	 * <p>
	 * {@code @Singleton} is load-bearing rather than an optimisation: {@code YoloLib} holds a single
	 * native detector for the whole process, so a second instance would not get a second model — it
	 * would get an exception on its first detection. One binding means one lazy initialization and one
	 * lock serializing the calls into it.
	 * </p>
	 *
	 * <p>
	 * Unlike {@code facedetect}'s detector providers this never returns null. There is no capability
	 * set to switch backends with, and a null here would only turn a misconfiguration into an NPE
	 * somewhere further along.
	 * </p>
	 */
	@Provides
	@Singleton
	public static ObjectDetector detector(ObjectDetectNodeOptions options) {
		return new YoloObjectDetector(options.getModelPath(), options.getLabelsPath(), options.isUseGpu(),
			options.getOnnxRuntimeLibPath());
	}
}
