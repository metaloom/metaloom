package io.metaloom.cortex.node.vlm;

/**
 * One reply from a vision-language endpoint.
 *
 * @param content      the assistant message content; never null, empty when the model returned nothing
 * @param finishReason the OpenAI {@code finish_reason}, e.g. {@code stop} or {@code length}; may be null
 * @param latencyMs    wall time of the HTTP call
 */
public record VlmReply(String content, String finishReason, long latencyMs) {

	/**
	 * Whether the model stopped because it ran into the output token limit rather than finishing its answer. For a document page this usually means the
	 * transcription is cut off and {@code maxTokens} needs raising.
	 */
	public boolean isTruncated() {
		return "length".equalsIgnoreCase(finishReason);
	}
}
