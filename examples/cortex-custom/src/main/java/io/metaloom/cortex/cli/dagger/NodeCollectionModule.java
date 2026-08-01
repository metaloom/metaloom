package io.metaloom.cortex.cli.dagger;

import dagger.Module;
import io.metaloom.cortex.node.captioning.CaptioningNodeModule;
import io.metaloom.cortex.node.consistency.ConsistencyNodeModule;
import io.metaloom.cortex.node.dedup.DedupNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.fp.FingerprintNodeModule;
import io.metaloom.cortex.node.hash.HashNodeModule;
import io.metaloom.cortex.node.llm.LLMNodeModule;
import io.metaloom.cortex.node.ocr.OCRNodeModule;
import io.metaloom.cortex.node.quality.QualityNodeModule;
import io.metaloom.cortex.node.scene.SceneDetectionNodeModule;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeModule;
import io.metaloom.cortex.node.tika.TikaNodeModule;
import io.metaloom.cortex.node.hello.HelloWorldNodeModule;

/**
 * The set of cortex nodes this custom daemon ships with.
 *
 * <p>
 * It keeps the built-in nodes from {@code cortex-core} and adds the example's own {@link HelloWorldNodeModule} (from the {@code cortex-custom-node}
 * module) — the extension point a downstream instance is expected to use: depend on your node module and include it here alongside the built-ins.
 * </p>
 */
@Module(includes = {
	HashNodeModule.class,
	ThumbnailNodeModule.class,
	FingerprintNodeModule.class,
	OCRNodeModule.class,
	FacedetectNodeModule.class,
	DedupNodeModule.class,
	TikaNodeModule.class,
	LLMNodeModule.class,
	SceneDetectionNodeModule.class,
	QualityNodeModule.class,
	HelloWorldNodeModule.class,
	CaptioningNodeModule.class,
	ConsistencyNodeModule.class })
public interface NodeCollectionModule {

}
