package io.metaloom.loom.nodes.spec;

import java.util.List;

/**
 * Static registry of all known content types that can flow between pipeline nodes.
 */
public final class ContentTypes {

	private ContentTypes() {
	}

	// ── Media (file-level) ──────────────────────────────────────────────
	public static final String MEDIA_ANY = "media/*";
	public static final String MEDIA_IMAGE = "media/image";
	public static final String MEDIA_VIDEO = "media/video";
	public static final String MEDIA_AUDIO = "media/audio";
	public static final String MEDIA_DOCUMENT = "media/document";

	// ── Scalar data ─────────────────────────────────────────────────────
	public static final String DATA_STRING = "data/string";
	public static final String DATA_INTEGER = "data/integer";
	public static final String DATA_NUMBER = "data/number";
	public static final String DATA_BOOLEAN = "data/boolean";
	public static final String DATA_HASH = "data/hash";
	public static final String DATA_PATH = "data/path";
	public static final String DATA_LONG = "data/long";

	// ── Structured / domain data ────────────────────────────────────────
	public static final String DATA_EMBEDDING = "data/embedding";
	public static final String DATA_FACEDETECTION = "data/facedetection";
	public static final String DATA_OBJECTDETECTION = "data/objectdetection";
	public static final String DATA_IMAGEAREA = "data/imagearea";
	public static final String DATA_TEXT = "data/text";
	public static final String DATA_TRANSCRIPT = "data/transcript";
	public static final String DATA_CAPTION = "data/caption";
	public static final String DATA_SCENE = "data/scene";
	public static final String DATA_FINGERPRINT = "data/fingerprint";
	public static final String DATA_THUMBNAIL = "data/thumbnail";
	public static final String DATA_QUALITY = "data/quality";

	// ── Control ─────────────────────────────────────────────────────────
	public static final String CONTROL_FILTER_RESULT = "control/filter_passed";

	/**
	 * Return all registered content types.
	 */
	public static List<ContentType> all() {
		return List.of(
			// Media
			new ContentType(MEDIA_ANY, "Any Media"),
			new ContentType(MEDIA_IMAGE, "Image", MEDIA_ANY),
			new ContentType(MEDIA_VIDEO, "Video", MEDIA_ANY),
			new ContentType(MEDIA_AUDIO, "Audio", MEDIA_ANY),
			new ContentType(MEDIA_DOCUMENT, "Document", MEDIA_ANY),

			// Scalar
			new ContentType(DATA_STRING, "String"),
			new ContentType(DATA_INTEGER, "Integer"),
			new ContentType(DATA_NUMBER, "Number"),
			new ContentType(DATA_BOOLEAN, "Boolean"),
			new ContentType(DATA_HASH, "Hash", DATA_STRING),
			new ContentType(DATA_PATH, "File Path", DATA_STRING),
			new ContentType(DATA_LONG, "Long"),

			// Structured
			new ContentType(DATA_EMBEDDING, "Embedding Vector"),
			new ContentType(DATA_FACEDETECTION, "Face Detection"),
			new ContentType(DATA_OBJECTDETECTION, "Object Detection"),
			new ContentType(DATA_IMAGEAREA, "Image Region"),
			new ContentType(DATA_TEXT, "Extracted Text"),
			new ContentType(DATA_TRANSCRIPT, "Audio Transcript"),
			new ContentType(DATA_CAPTION, "Image Caption"),
			new ContentType(DATA_SCENE, "Scene Boundaries"),
			new ContentType(DATA_FINGERPRINT, "Media Fingerprint"),
			new ContentType(DATA_THUMBNAIL, "Thumbnail Image"),
			new ContentType(DATA_QUALITY, "Quality Metrics"),

			// Control
			new ContentType(CONTROL_FILTER_RESULT, "Filter Result (bool)")
		);
	}
}
