package io.metaloom.cortex.node.vlm;

import io.vertx.core.json.JsonObject;

/**
 * Parsed form of an olmOCR page reply.
 *
 * <p>
 * olmOCR models (e.g. {@code allenai/olmOCR-2-7B-1025-FP8}) are asked to return markdown with a YAML front matter section on top:
 * </p>
 *
 * <pre>
 * ---
 * primary_language: en
 * is_rotation_valid: True
 * rotation_correction: 0
 * is_table: False
 * is_diagram: False
 * ---
 * &lt;the transcribed page as markdown&gt;
 * </pre>
 *
 * @param primaryLanguage    detected language of the page, or null when the model did not report one
 * @param rotationValid      whether the page was fed in at a readable orientation
 * @param rotationCorrection clockwise rotation in degrees needed to make the page readable, one of 0/90/180/270
 * @param table              whether the page is (mostly) a table
 * @param diagram            whether the page is (mostly) a diagram
 * @param naturalText        the transcribed page text; never null, empty when the model returned nothing
 * @param truncated          whether the model stopped because it hit the token limit rather than finishing
 */
public record OlmOcrResponse(
	String primaryLanguage,
	boolean rotationValid,
	int rotationCorrection,
	boolean table,
	boolean diagram,
	String naturalText,
	boolean truncated) {

	/**
	 * Whether the model reported that the page needs to be rotated before it can be read properly.
	 */
	public boolean needsRotation() {
		return !rotationValid && (rotationCorrection == 90 || rotationCorrection == 180 || rotationCorrection == 270);
	}

	/**
	 * Return a copy with the truncation flag set to the given value.
	 */
	public OlmOcrResponse withTruncated(boolean flag) {
		return new OlmOcrResponse(primaryLanguage, rotationValid, rotationCorrection, table, diagram, naturalText, flag);
	}

	/**
	 * The payload stored in the {@code asset_json_comp} row. Keys mirror the olmOCR front matter names so the stored document stays recognisable against
	 * the upstream model card, plus {@code natural_text} for the page body.
	 */
	public JsonObject toJson() {
		return new JsonObject()
			.put("primary_language", primaryLanguage)
			.put("is_rotation_valid", rotationValid)
			.put("rotation_correction", rotationCorrection)
			.put("is_table", table)
			.put("is_diagram", diagram)
			.put("natural_text", naturalText)
			.put("truncated", truncated);
	}
}
