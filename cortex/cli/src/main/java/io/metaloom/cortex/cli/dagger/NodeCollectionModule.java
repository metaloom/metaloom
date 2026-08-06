package io.metaloom.cortex.cli.dagger;

import dagger.Module;
import io.metaloom.cortex.node.captioning.CaptioningNodeModule;
import io.metaloom.cortex.node.consistency.ConsistencyNodeModule;
import io.metaloom.cortex.node.dedup.DedupNodeModule;
import io.metaloom.cortex.node.depthmap.DepthmapNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.objectdetect.ObjectDetectNodeModule;
import io.metaloom.cortex.node.fp.FingerprintNodeModule;
import io.metaloom.cortex.node.hash.HashNodeModule;
import io.metaloom.cortex.node.imagegen.ImageGenNodeModule;
import io.metaloom.cortex.node.imagemanip.ImageManipulationNodeModule;
import io.metaloom.cortex.node.videogen.VideoGenNodeModule;
import io.metaloom.cortex.node.llm.LLMNodeModule;
import io.metaloom.cortex.node.ocr.OCRNodeModule;
import io.metaloom.cortex.node.quality.QualityNodeModule;
import io.metaloom.cortex.node.scene.SceneDetectionNodeModule;
import io.metaloom.cortex.node.color.DominantColorNodeModule;
import io.metaloom.cortex.node.scenelayout.SceneLayoutNodeModule;
import io.metaloom.cortex.node.script.ScriptNodeModule;
import io.metaloom.cortex.node.sentiment.SentimentNodeModule;
import io.metaloom.cortex.node.source.cloud.CloudSourceNodeModule;
import io.metaloom.cortex.node.source.fs.FilesystemSourceNodeModule;
import io.metaloom.cortex.node.sink.s3.S3SinkNodeModule;
import io.metaloom.cortex.node.source.s3.S3SourceNodeModule;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeModule;
import io.metaloom.cortex.node.metadata.MetadataNodeModule;
import io.metaloom.cortex.node.tag.TagNodeModule;
import io.metaloom.cortex.node.tika.TikaNodeModule;
import io.metaloom.cortex.node.guard.GuardNodeModule;
import io.metaloom.cortex.node.translate.TranslateNodeModule;
import io.metaloom.cortex.node.tts.TtsNodeModule;
import io.metaloom.cortex.node.vlm.VlmNodeModule;
import io.metaloom.cortex.node.filter.FilterNodeModule;
import io.metaloom.cortex.node.watermark.WatermarkNodeModule;
import io.metaloom.cortex.node.whisper.WhisperNodeModule;

@Module(includes = {
	FilesystemSourceNodeModule.class,
	S3SourceNodeModule.class,
	CloudSourceNodeModule.class,
	S3SinkNodeModule.class,
	HashNodeModule.class,
	ThumbnailNodeModule.class,
	FingerprintNodeModule.class,
	OCRNodeModule.class,
	FacedetectNodeModule.class,
	ObjectDetectNodeModule.class,
	DedupNodeModule.class,
	MetadataNodeModule.class,
	TikaNodeModule.class,
	LLMNodeModule.class,
	VlmNodeModule.class,
	SceneDetectionNodeModule.class,
	QualityNodeModule.class,
	CaptioningNodeModule.class,
	ImageGenNodeModule.class,
	ImageManipulationNodeModule.class,
	VideoGenNodeModule.class,
	ConsistencyNodeModule.class,
	WhisperNodeModule.class,
	TtsNodeModule.class,
	SentimentNodeModule.class,
	ScriptNodeModule.class,
	DepthmapNodeModule.class,
	SceneLayoutNodeModule.class,
	DominantColorNodeModule.class,
	WatermarkNodeModule.class,
	FilterNodeModule.class,
	TranslateNodeModule.class,
	GuardNodeModule.class,
	TagNodeModule.class })
public interface NodeCollectionModule {

}
