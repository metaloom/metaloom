package io.metaloom.loom.nodes.spec;

import java.util.List;

/**
 * The vocabulary of content types that can flow between pipeline nodes.
 *
 * <p>
 * A content type id is always <code>family/subtype</code>. The wildcard <code>family/&#42;</code> is the root of a family and matches any subtype
 * within it. Assignability is computed structurally by {@link ContentTypeLattice} — there is deliberately no parent pointer to keep consistent, and
 * assignability never crosses families.
 * </p>
 *
 * <p>
 * This class replaces the former <code>ContentTypes</code> holder, whose <code>superType</code> chain was evaluated by no Java code at all.
 * </p>
 */
public final class ContentTypeRegistry {

	private ContentTypeRegistry() {
	}

	// ── media ── the item itself, resolvable through a MediaRef ──────────
	public static final String MEDIA_ANY = "media/*";
	public static final String MEDIA_IMAGE = "media/image";
	public static final String MEDIA_VIDEO = "media/video";
	public static final String MEDIA_AUDIO = "media/audio";
	public static final String MEDIA_DOCUMENT = "media/document";

	// ── text ── human-readable prose ─────────────────────────────────────
	public static final String TEXT_ANY = "text/*";
	public static final String TEXT_PLAIN = "text/plain";
	public static final String TEXT_TRANSCRIPT = "text/transcript";
	public static final String TEXT_CAPTION = "text/caption";

	// ── detection ── bounding boxes ──────────────────────────────────────
	public static final String DETECTION_ANY = "detection/*";
	public static final String DETECTION_FACE = "detection/face";
	public static final String DETECTION_OBJECT = "detection/object";
	public static final String DETECTION_REGION = "detection/region";

	// ── hash ── content identity, split per algorithm ────────────────────
	public static final String HASH_ANY = "hash/*";
	public static final String HASH_MD5 = "hash/md5";
	public static final String HASH_SHA256 = "hash/sha256";
	public static final String HASH_SHA512 = "hash/sha512";
	public static final String HASH_CHUNK = "hash/chunk";
	public static final String HASH_FINGERPRINT = "hash/fingerprint";

	// ── scalar ── primitives ─────────────────────────────────────────────
	public static final String SCALAR_ANY = "scalar/*";
	public static final String SCALAR_STRING = "scalar/string";
	/** Always 64-bit. Merges the former {@code data/integer} and {@code data/long}. */
	public static final String SCALAR_INTEGER = "scalar/integer";
	public static final String SCALAR_NUMBER = "scalar/number";
	public static final String SCALAR_BOOLEAN = "scalar/boolean";

	// ── artifact ── a file produced by a node, local to the worker ───────
	public static final String ARTIFACT_ANY = "artifact/*";
	public static final String ARTIFACT_IMAGE = "artifact/image";
	public static final String ARTIFACT_VIDEO = "artifact/video";
	public static final String ARTIFACT_AUDIO = "artifact/audio";
	public static final String ARTIFACT_FILE = "artifact/file";

	// ── struct ── structured JSON payloads ───────────────────────────────
	public static final String STRUCT_ANY = "struct/*";
	public static final String STRUCT_EMBEDDING = "struct/embedding";
	public static final String STRUCT_SEGMENTS = "struct/segments";
	public static final String STRUCT_SCENE_LAYOUT = "struct/scene-layout";
	public static final String STRUCT_QUALITY = "struct/quality";
	public static final String STRUCT_DEPTHMAP = "struct/depthmap";
	/** Spatial per-object segmentation masks. Deliberately not {@link #STRUCT_SEGMENTS}, which is time-coded. */
	public static final String STRUCT_MASKS = "struct/masks";
	public static final String STRUCT_COLOR = "struct/color";
	public static final String STRUCT_JSON = "struct/json";

	// ── control ── engine routing signals ────────────────────────────────
	public static final String CONTROL_ANY = "control/*";
	public static final String CONTROL_FILTER = "control/filter";

	/** The eight families, in palette order. Each is also the editor's colour key. */
	public static final List<String> FAMILIES = List.of(
		"media", "text", "detection", "hash", "scalar", "artifact", "struct", "control");

	/**
	 * Every registered content type, in palette order. Served to the UI by the node-descriptor endpoint.
	 */
	public static List<ContentType> all() {
		return List.of(
			new ContentType(MEDIA_ANY, "Any Media", "Any media item, regardless of kind"),
			new ContentType(MEDIA_IMAGE, "Image", "A still image"),
			new ContentType(MEDIA_VIDEO, "Video", "A video file"),
			new ContentType(MEDIA_AUDIO, "Audio", "An audio file"),
			new ContentType(MEDIA_DOCUMENT, "Document", "A document (PDF, office file)"),

			new ContentType(TEXT_ANY, "Any Text", "Any human-readable text"),
			new ContentType(TEXT_PLAIN, "Text", "Plain prose"),
			new ContentType(TEXT_TRANSCRIPT, "Transcript", "Speech transcribed to text, with timings"),
			new ContentType(TEXT_CAPTION, "Caption", "A generated description of an image or video"),

			new ContentType(DETECTION_ANY, "Any Detection", "Any bounding-box detection"),
			new ContentType(DETECTION_FACE, "Face Detection", "A detected face with its box"),
			new ContentType(DETECTION_OBJECT, "Object Detection", "A detected object with its box"),
			new ContentType(DETECTION_REGION, "Image Region", "A region of interest with no class"),

			new ContentType(HASH_ANY, "Any Hash", "A content hash of any algorithm"),
			new ContentType(HASH_MD5, "MD5", "MD5 content hash"),
			new ContentType(HASH_SHA256, "SHA-256", "SHA-256 content hash"),
			new ContentType(HASH_SHA512, "SHA-512", "SHA-512 content hash"),
			new ContentType(HASH_CHUNK, "Chunk Hash", "Chunk-wise hash used for dedup"),
			new ContentType(HASH_FINGERPRINT, "Fingerprint", "Perceptual media fingerprint"),

			new ContentType(SCALAR_ANY, "Any Scalar", "Any primitive value"),
			new ContentType(SCALAR_STRING, "String", "A string value, flag or identifier"),
			new ContentType(SCALAR_INTEGER, "Integer", "A whole number (always 64-bit)"),
			new ContentType(SCALAR_NUMBER, "Number", "A floating-point number"),
			new ContentType(SCALAR_BOOLEAN, "Boolean", "A true/false value"),

			new ContentType(ARTIFACT_ANY, "Any Artifact", "Any file produced by a node"),
			new ContentType(ARTIFACT_IMAGE, "Image Artifact", "A generated image file"),
			new ContentType(ARTIFACT_VIDEO, "Video Artifact", "A generated video file"),
			new ContentType(ARTIFACT_AUDIO, "Audio Artifact", "A generated audio file"),
			new ContentType(ARTIFACT_FILE, "File Artifact", "A generated file of any kind"),

			new ContentType(STRUCT_ANY, "Any Structure", "Any structured JSON payload"),
			new ContentType(STRUCT_EMBEDDING, "Embedding Vector", "A numeric embedding vector"),
			new ContentType(STRUCT_SEGMENTS, "Timeframes", "Time-coded segments of a media item"),
			new ContentType(STRUCT_SCENE_LAYOUT, "Scene Layout", "Spatial relations between detected objects"),
			new ContentType(STRUCT_QUALITY, "Quality Metrics", "Resolution, blurriness and bitrate metrics"),
			new ContentType(STRUCT_DEPTHMAP, "Depth Map", "Depth-map metadata"),
			new ContentType(STRUCT_MASKS, "Segmentation Masks",
				"Per-object segmentation masks: area, score, box and the worker-local mask file for each"),
			new ContentType(STRUCT_COLOR, "Dominant Colour", "Dominant-colour palette and names"),
			new ContentType(STRUCT_JSON, "JSON", "A structured payload with no more specific type"),

			new ContentType(CONTROL_ANY, "Any Control", "Any engine routing signal"),
			new ContentType(CONTROL_FILTER, "Filter Verdict", "The pass/reject verdict of a filter node"));
	}

	/**
	 * Whether the given id is a known content type.
	 */
	public static boolean isKnown(String id) {
		return all().stream().anyMatch(t -> t.getId().equals(id));
	}
}
