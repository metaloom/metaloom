package io.metaloom.cortex.node.vlm;

/**
 * How the raw reply of a vision-language model is turned into the JSON payload that gets stored on the asset.
 */
public enum VlmResponseFormat {

	/** The reply is plain text and is stored as <code>{"text": "..."}</code>. */
	TEXT,

	/** The reply is a JSON object (optionally fenced in a markdown code block) and is stored as-is. */
	JSON,

	/**
	 * The reply follows the olmOCR convention: a YAML front matter block delimited by <code>---</code> lines, followed by the transcribed page as markdown.
	 * See {@link OlmOcrResponse}.
	 */
	OLMOCR;
}
