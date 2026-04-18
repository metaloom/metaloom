package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Factory that creates and registers all built-in {@link NodeDescriptor} instances
 * for the known CortexNode implementations and pipeline filter nodes.
 */
public final class CortexNodeDescriptors {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	private CortexNodeDescriptors() {
	}

	/**
	 * Register all built-in node descriptors into the given registry.
	 */
	public static void registerAll(NodeDescriptorRegistry registry) {
		registerSourceNodes(registry);
		registerHashNodes(registry);
		registerAnalysisNodes(registry);
		registerTransformNodes(registry);
		registerOutputNodes(registry);
		registerFilterNodes(registry);
	}

	// ── Source Nodes ─────────────────────────────────────────────────────

	private static void registerSourceNodes(NodeDescriptorRegistry registry) {
		registry.register(new NodeDescriptor()
			.setKind("filesystem-source")
			.setName("Filesystem Source")
			.setDescription("Reads media files from the filesystem as pipeline input.")
			.setIcon("folder_open")
			.setCategory(SOURCE)
			.setInputs(List.of())
			.setOutputs(List.of(
				new NodeOutput("media", MEDIA_ANY)))
			.setParameters(List.of(
				commonEnabled(),
				new NodeParameter().setKey("path").setType(STRING).setLabel("Path")
					.setDescription("Root directory to scan for media files")))
			.setDefaultConcurrency(1)
			.setDefaultMode(SEQUENTIAL)
			.setEvents(STANDARD_EVENTS));

		registry.register(new NodeDescriptor()
			.setKind("loom-fetch")
			.setName("Loom Fetch")
			.setDescription("Fetches media references from the Loom backend.")
			.setIcon("cloud_download")
			.setCategory(SOURCE)
			.setInputs(List.of())
			.setOutputs(List.of(
				new NodeOutput("media", MEDIA_ANY)))
			.setParameters(List.of(
				commonEnabled()))
			.setDefaultConcurrency(1)
			.setDefaultMode(SEQUENTIAL)
			.setEvents(STANDARD_EVENTS));
	}

	// ── Hash Nodes ───────────────────────────────────────────────────────

	private static void registerHashNodes(NodeDescriptorRegistry registry) {
		registry.register(new NodeDescriptor()
			.setKind("md5")
			.setName("MD5 Hash")
			.setDescription("Compute the MD5 hash of the media file.")
			.setIcon("fingerprint")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("md5", DATA_HASH)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		registry.register(new NodeDescriptor()
			.setKind("sha256")
			.setName("SHA-256 Hash")
			.setDescription("Compute the SHA-256 hash of the media file.")
			.setIcon("fingerprint")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("sha256", DATA_HASH)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		registry.register(new NodeDescriptor()
			.setKind("sha512")
			.setName("SHA-512 Hash")
			.setDescription("Compute the SHA-512 hash of the media file.")
			.setIcon("fingerprint")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("sha512", DATA_HASH)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		registry.register(new NodeDescriptor()
			.setKind("chunk-hash")
			.setName("Chunk Hash")
			.setDescription("Compute a hash over fixed-size chunks of the media file.")
			.setIcon("fingerprint")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("chunk_hash", DATA_HASH)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));
	}

	// ── Analysis Nodes ───────────────────────────────────────────────────

	private static void registerAnalysisNodes(NodeDescriptorRegistry registry) {

		// Fingerprint
		registry.register(new NodeDescriptor()
			.setKind("fingerprint")
			.setName("Fingerprint")
			.setDescription("Compute a perceptual fingerprint of the media for deduplication or similarity search.")
			.setIcon("grain")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("fingerprint", DATA_FINGERPRINT)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(2)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Face Detection
		registry.register(new NodeDescriptor()
			.setKind("facedetect")
			.setName("Face Detection")
			.setDescription("Detect and cluster faces in images and video frames.")
			.setIcon("face")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_IMAGE, true),
				new NodeInput("media", MEDIA_VIDEO, true)))
			.setOutputs(List.of(
				new NodeOutput("face_count", DATA_INTEGER),
				new NodeOutput("facedetect_flag", DATA_STRING)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed(),
				new NodeParameter().setKey("videoChopRate").setType(INTEGER).setDefaultValue(5)
					.setLabel("Video Chop Rate").setDescription("Process every Nth video frame").setMin(1),
				new NodeParameter().setKey("faceClusterMinimum").setType(INTEGER).setDefaultValue(2)
					.setLabel("Min Cluster Size").setDescription("Minimum detections to form a cluster"),
				new NodeParameter().setKey("faceClusterEPS").setType(NUMBER).setDefaultValue(0.6)
					.setLabel("Cluster Radius").setDescription("DBSCAN cluster radius threshold")
					.setMin(0.0).setMax(2.0).setStep(0.05),
				new NodeParameter().setKey("videoScaleSize").setType(INTEGER).setDefaultValue(384)
					.setLabel("Scale Size (px)").setDescription("Rescale video frames to this size"),
				new NodeParameter().setKey("minFaceHeightFactor").setType(NUMBER).setDefaultValue(0.05)
					.setLabel("Min Face Height Factor").setMin(0.0).setMax(1.0),
				new NodeParameter().setKey("inspirefacePackPath").setType(STRING).setDefaultValue("packs/Pikachu")
					.setLabel("Model Pack Path"),
				new NodeParameter().setKey("capabilities").setType(ENUM_SET)
					.setValues(List.of("INSPIREFACE", "DLIB"))
					.setDefaultValue(List.of("INSPIREFACE"))
					.setLabel("Backends")))
			.setDefaultConcurrency(2)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Face Description
		registry.register(new NodeDescriptor()
			.setKind("facedescription")
			.setName("Face Description")
			.setDescription("Generate textual descriptions of detected faces.")
			.setIcon("face_retouching_natural")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("facedetection", DATA_FACEDETECTION, true)))
			.setOutputs(List.of(
				new NodeOutput("face_description", DATA_TEXT)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(2)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Quality
		registry.register(new NodeDescriptor()
			.setKind("quality")
			.setName("Quality Analysis")
			.setDescription("Analyze media quality: blurriness, resolution, bitrate.")
			.setIcon("high_quality")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("blurriness", DATA_NUMBER),
				new NodeOutput("image_width", DATA_INTEGER),
				new NodeOutput("image_height", DATA_INTEGER),
				new NodeOutput("video_width", DATA_INTEGER),
				new NodeOutput("video_height", DATA_INTEGER),
				new NodeOutput("video_fps", DATA_NUMBER),
				new NodeOutput("video_frame_count", DATA_LONG),
				new NodeOutput("quality_flag", DATA_STRING)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed(),
				new NodeParameter().setKey("checkBlurriness").setType(BOOLEAN).setDefaultValue(true)
					.setLabel("Check Blurriness"),
				new NodeParameter().setKey("checkResolution").setType(BOOLEAN).setDefaultValue(true)
					.setLabel("Check Resolution"),
				new NodeParameter().setKey("checkVideoBitrate").setType(BOOLEAN).setDefaultValue(true)
					.setLabel("Check Video Bitrate"),
				new NodeParameter().setKey("checkAudioBitrate").setType(BOOLEAN).setDefaultValue(true)
					.setLabel("Check Audio Bitrate")))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Consistency
		registry.register(new NodeDescriptor()
			.setKind("consistency")
			.setName("Consistency Check")
			.setDescription("Check media file integrity (zero chunk detection, completeness).")
			.setIcon("verified")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("zero_chunk_count", DATA_LONG),
				new NodeOutput("is_complete", DATA_BOOLEAN)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Scene Detection
		registry.register(new NodeDescriptor()
			.setKind("scene-detection")
			.setName("Scene Detection")
			.setDescription("Detect scene boundaries in video files.")
			.setIcon("movie_filter")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_VIDEO, true)))
			.setOutputs(List.of(
				new NodeOutput("scene_detection", DATA_SCENE)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(2)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Tika
		registry.register(new NodeDescriptor()
			.setKind("tika")
			.setName("Tika Extraction")
			.setDescription("Extract metadata and text content using Apache Tika.")
			.setIcon("description")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("tika_flags", DATA_STRING),
				new NodeOutput("tika_content", DATA_TEXT)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Whisper (Speech-to-Text)
		registry.register(new NodeDescriptor()
			.setKind("whisper")
			.setName("Whisper (Speech-to-Text)")
			.setDescription("Transcribe audio/video speech to text using Whisper.")
			.setIcon("mic")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_AUDIO, true),
				new NodeInput("media", MEDIA_VIDEO, true)))
			.setOutputs(List.of(
				new NodeOutput("whisper_result", DATA_TRANSCRIPT)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed(),
				new NodeParameter().setKey("modelPath").setType(STRING)
					.setDefaultValue("models/ggml-large-v3-turbo.bin")
					.setLabel("Model Path").setDescription("Path to the Whisper model file"),
				new NodeParameter().setKey("temperature").setType(NUMBER).setDefaultValue(0.0)
					.setLabel("Temperature").setMin(0.0).setMax(1.0).setStep(0.1),
				new NodeParameter().setKey("temperatureInc").setType(NUMBER).setDefaultValue(0.2)
					.setLabel("Temperature Increment").setMin(0.0).setMax(1.0).setStep(0.05),
				new NodeParameter().setKey("language").setType(STRING)
					.setLabel("Language").setDescription("Target language code (e.g. 'en', 'de')"),
				new NodeParameter().setKey("useGpu").setType(BOOLEAN).setDefaultValue(true)
					.setLabel("Use GPU"),
				new NodeParameter().setKey("gpuDevice").setType(INTEGER).setDefaultValue(0)
					.setLabel("GPU Device Index").setMin(0)))
			.setDefaultConcurrency(1)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// OCR
		registry.register(new NodeDescriptor()
			.setKind("ocr")
			.setName("OCR")
			.setDescription("Extract text from images using optical character recognition.")
			.setIcon("text_fields")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_IMAGE, true)))
			.setOutputs(List.of(
				new NodeOutput("ocr_text", DATA_TEXT)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(2)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// LLM
		registry.register(new NodeDescriptor()
			.setKind("llm")
			.setName("LLM (Large Language Model)")
			.setDescription("Process media through an LLM (e.g. Ollama) with configurable prompts.")
			.setIcon("psychology")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("llm_result", DATA_TEXT)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed(),
				new NodeParameter().setKey("ollamaUrl").setType(STRING)
					.setDefaultValue("http://127.0.0.1:11434")
					.setLabel("Ollama URL").setDescription("URL of the Ollama service")))
			.setDefaultConcurrency(1)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Captioning
		registry.register(new NodeDescriptor()
			.setKind("captioning")
			.setName("Image Captioning")
			.setDescription("Generate a textual caption for an image.")
			.setIcon("image_search")
			.setCategory(ANALYSIS)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_IMAGE, true)))
			.setOutputs(List.of(
				new NodeOutput("caption_result", DATA_CAPTION)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed()))
			.setDefaultConcurrency(1)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));
	}

	// ── Transform Nodes ──────────────────────────────────────────────────

	private static void registerTransformNodes(NodeDescriptorRegistry registry) {

		// Thumbnail
		registry.register(new NodeDescriptor()
			.setKind("thumbnail")
			.setName("Thumbnail Generator")
			.setDescription("Generate a thumbnail grid from video or image content.")
			.setIcon("grid_view")
			.setCategory(TRANSFORM)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("thumbnail_flag", DATA_STRING),
				new NodeOutput("thumbnail_path", DATA_PATH)))
			.setParameters(List.of(
				commonEnabled(),
				commonProcessIncomplete(),
				commonRetryFailed(),
				new NodeParameter().setKey("cols").setType(INTEGER).setDefaultValue(6)
					.setLabel("Grid Columns").setMin(1).setMax(20),
				new NodeParameter().setKey("rows").setType(INTEGER).setDefaultValue(1)
					.setLabel("Grid Rows").setMin(1).setMax(20),
				new NodeParameter().setKey("tileSize").setType(INTEGER).setDefaultValue(384)
					.setLabel("Tile Size (px)").setMin(32).setMax(1024)))
			.setDefaultConcurrency(2)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));
	}

	// ── Output Nodes ─────────────────────────────────────────────────────

	private static void registerOutputNodes(NodeDescriptorRegistry registry) {

		// Loom Sync
		registry.register(new NodeDescriptor()
			.setKind("loom")
			.setName("Loom Sync")
			.setDescription("Synchronize processing results back to the Loom backend.")
			.setIcon("cloud_upload")
			.setCategory(OUTPUT)
			.setInputs(List.of(
				new NodeInput("results", DATA_STRING, false)))
			.setOutputs(List.of())
			.setParameters(List.of(
				commonEnabled()))
			.setDefaultConcurrency(1)
			.setDefaultMode(PARALLEL)
			.setDefaultBlocking(false)
			.setEvents(STANDARD_EVENTS));

		// Hash Dedup
		registry.register(new NodeDescriptor()
			.setKind("hash-dedup")
			.setName("Hash Deduplication")
			.setDescription("Detect and move duplicate files based on hash equality.")
			.setIcon("file_copy")
			.setCategory(OUTPUT)
			.setInputs(List.of(
				new NodeInput("hash", DATA_HASH, true)))
			.setOutputs(List.of())
			.setParameters(List.of(
				commonEnabled(),
				new NodeParameter().setKey("dupFolder").setType(STRING).setDefaultValue("duplicates")
					.setLabel("Duplicates Folder").setDescription("Target folder for duplicate files")))
			.setDefaultConcurrency(1)
			.setDefaultMode(SEQUENTIAL)
			.setEvents(STANDARD_EVENTS));

		// Fingerprint Dedup
		registry.register(new NodeDescriptor()
			.setKind("fingerprint-dedup")
			.setName("Fingerprint Deduplication")
			.setDescription("Detect and move near-duplicate files based on perceptual fingerprint similarity.")
			.setIcon("content_copy")
			.setCategory(OUTPUT)
			.setInputs(List.of(
				new NodeInput("fingerprint", DATA_FINGERPRINT, true)))
			.setOutputs(List.of())
			.setParameters(List.of(
				commonEnabled(),
				new NodeParameter().setKey("dupFolder").setType(STRING).setDefaultValue("duplicates")
					.setLabel("Duplicates Folder").setDescription("Target folder for duplicate files")))
			.setDefaultConcurrency(1)
			.setDefaultMode(SEQUENTIAL)
			.setEvents(STANDARD_EVENTS));
	}

	// ── Filter Nodes ─────────────────────────────────────────────────────

	private static void registerFilterNodes(NodeDescriptorRegistry registry) {

		// MIME Type Filter
		registry.register(new NodeDescriptor()
			.setKind("filter-mimetype")
			.setName("MIME Type Filter")
			.setDescription("Filter media by MIME type (e.g. only process images or videos).")
			.setIcon("filter_alt")
			.setCategory(FILTER)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
			.setParameters(List.of(
				commonEnabled(),
				new NodeParameter().setKey("allowedTypes").setType(STRING)
					.setLabel("Allowed MIME Types")
					.setDescription("Comma-separated list of allowed MIME types (e.g. 'image/*,video/*')")))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Date Filter
		registry.register(new NodeDescriptor()
			.setKind("filter-date")
			.setName("Date Filter")
			.setDescription("Filter media by file creation or modification date.")
			.setIcon("date_range")
			.setCategory(FILTER)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
			.setParameters(List.of(
				commonEnabled(),
				new NodeParameter().setKey("after").setType(STRING)
					.setLabel("After Date").setDescription("Only pass files modified after this date (ISO-8601)"),
				new NodeParameter().setKey("before").setType(STRING)
					.setLabel("Before Date").setDescription("Only pass files modified before this date (ISO-8601)")))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Size Filter
		registry.register(new NodeDescriptor()
			.setKind("filter-size")
			.setName("File Size Filter")
			.setDescription("Filter media by file size.")
			.setIcon("straighten")
			.setCategory(FILTER)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
			.setParameters(List.of(
				commonEnabled(),
				new NodeParameter().setKey("minSize").setType(INTEGER)
					.setLabel("Min Size (bytes)").setDescription("Minimum file size in bytes").setMin(0),
				new NodeParameter().setKey("maxSize").setType(INTEGER)
					.setLabel("Max Size (bytes)").setDescription("Maximum file size in bytes").setMin(0)))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Duplicate Filter
		registry.register(new NodeDescriptor()
			.setKind("filter-duplicate")
			.setName("Duplicate Filter")
			.setDescription("Filter out media that has already been processed.")
			.setIcon("block")
			.setCategory(FILTER)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
			.setParameters(List.of(
				commonEnabled()))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Blacklist Filter
		registry.register(new NodeDescriptor()
			.setKind("filter-blacklist")
			.setName("Blacklist Filter")
			.setDescription("Filter out media whose upstream text output matches a blacklisted term.")
			.setIcon("playlist_remove")
			.setCategory(FILTER)
			.setInputs(List.of(
				new NodeInput("text", DATA_TEXT, true)))
			.setOutputs(List.of(
				new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
			.setParameters(List.of(
				commonEnabled(),
				new NodeParameter().setKey("terms").setType(STRING)
					.setLabel("Blacklisted Terms")
					.setDescription("Comma-separated list of blacklisted terms")))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Quality Filter
		registry.register(new NodeDescriptor()
			.setKind("filter-quality")
			.setName("Quality Filter")
			.setDescription("Filter media based on quality metrics (resolution, blurriness, bitrate).")
			.setIcon("tune")
			.setCategory(FILTER)
			.setInputs(List.of(
				new NodeInput("quality", DATA_QUALITY, true)))
			.setOutputs(List.of(
				new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
			.setParameters(List.of(
				commonEnabled(),
				new NodeParameter().setKey("minWidth").setType(INTEGER)
					.setLabel("Min Width (px)").setMin(0),
				new NodeParameter().setKey("minHeight").setType(INTEGER)
					.setLabel("Min Height (px)").setMin(0),
				new NodeParameter().setKey("maxBlurriness").setType(NUMBER)
					.setLabel("Max Blurriness").setMin(0.0)))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Threshold Filter
		registry.register(new NodeDescriptor()
			.setKind("filter-threshold")
			.setName("Threshold Filter")
			.setDescription("Filter media based on a numeric upstream output exceeding a threshold.")
			.setIcon("linear_scale")
			.setCategory(FILTER)
			.setInputs(List.of(
				new NodeInput("value", DATA_NUMBER, true)))
			.setOutputs(List.of(
				new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
			.setParameters(List.of(
				commonEnabled(),
				new NodeParameter().setKey("threshold").setType(NUMBER)
					.setLabel("Threshold").setDescription("Minimum value to pass the filter")))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));

		// Asset Attribute Filter
		registry.register(new NodeDescriptor()
			.setKind("filter-asset-attribute")
			.setName("Asset Attribute Filter")
			.setDescription("Filter media based on asset attributes stored in Loom.")
			.setIcon("label")
			.setCategory(FILTER)
			.setInputs(List.of(
				new NodeInput("media", MEDIA_ANY, true)))
			.setOutputs(List.of(
				new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
			.setParameters(List.of(
				commonEnabled(),
				new NodeParameter().setKey("attributeKey").setType(STRING)
					.setLabel("Attribute Key").setDescription("The asset attribute to check"),
				new NodeParameter().setKey("attributeValue").setType(STRING)
					.setLabel("Expected Value").setDescription("The value to match against")))
			.setDefaultConcurrency(4)
			.setDefaultMode(PARALLEL)
			.setEvents(STANDARD_EVENTS));
	}

	// ── Common parameters (shared by all / most nodes) ───────────────────

	private static NodeParameter commonEnabled() {
		return new NodeParameter()
			.setKey("enabled")
			.setType(BOOLEAN)
			.setDefaultValue(true)
			.setLabel("Enabled")
			.setDescription("Whether this node is active in the pipeline");
	}

	private static NodeParameter commonProcessIncomplete() {
		return new NodeParameter()
			.setKey("processIncomplete")
			.setType(BOOLEAN)
			.setDefaultValue(false)
			.setLabel("Process Incomplete")
			.setDescription("Process media files that are still being written");
	}

	private static NodeParameter commonRetryFailed() {
		return new NodeParameter()
			.setKey("retryFailed")
			.setType(BOOLEAN)
			.setDefaultValue(false)
			.setLabel("Retry Failed")
			.setDescription("Retry processing media that previously failed");
	}
}
