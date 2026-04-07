package io.metaloom.cortex.cli.dagger;

import dagger.Module;
import io.metaloom.cortex.node.captioning.CaptioningNodeModule;
import io.metaloom.cortex.node.consistency.ConsistencyNodeModule;
import io.metaloom.cortex.node.dedup.DedupNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.fp.FingerprintNodeModule;
import io.metaloom.cortex.node.hash.HashNodeModule;
import io.metaloom.cortex.node.llm.LLMNodeModule;
import io.metaloom.cortex.node.loom.LoomNodeModule;
import io.metaloom.cortex.node.ocr.OCRNodeModule;
import io.metaloom.cortex.node.scene.SceneDetectionNodeModule;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeModule;
import io.metaloom.cortex.node.tika.TikaNodeModule;

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
	LoomNodeModule.class,
	CaptioningNodeModule.class,
	ConsistencyNodeModule.class })
public interface NodeCollectionModule {

}
