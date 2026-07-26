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
import io.metaloom.cortex.node.quality.QualityNodeModule;
import io.metaloom.cortex.node.scene.SceneDetectionNodeModule;
import io.metaloom.cortex.node.source.fs.FilesystemSourceNodeModule;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeModule;
import io.metaloom.cortex.node.tika.TikaNodeModule;
import io.metaloom.cortex.node.tts.TtsNodeModule;
import io.metaloom.cortex.node.vlm.VlmNodeModule;
import io.metaloom.cortex.node.whisper.WhisperNodeModule;

@Module(includes = {
	FilesystemSourceNodeModule.class,
	HashNodeModule.class,
	ThumbnailNodeModule.class,
	FingerprintNodeModule.class,
	OCRNodeModule.class,
	FacedetectNodeModule.class,
	DedupNodeModule.class,
	TikaNodeModule.class,
	LLMNodeModule.class,
	VlmNodeModule.class,
	SceneDetectionNodeModule.class,
	LoomNodeModule.class,
	QualityNodeModule.class,
	CaptioningNodeModule.class,
	ConsistencyNodeModule.class,
	WhisperNodeModule.class,
	TtsNodeModule.class })
public interface NodeCollectionModule {

}
