package io.metaloom.cortex.api.node.payload;

/**
 * Payload carrying a text value. Used for transcripts (e.g. Whisper output),
 * LLM text input/output, vision-LLM descriptions, OCR results, etc.
 */
public interface TextPayload extends Payload {

	/**
	 * The text content.
	 */
	String text();

	static TextPayload of(String text) {
		return () -> text;
	}
}
