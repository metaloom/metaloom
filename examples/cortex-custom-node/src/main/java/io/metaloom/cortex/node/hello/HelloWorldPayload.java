package io.metaloom.cortex.node.hello;

import io.metaloom.cortex.api.node.payload.Payload;

/**
 * Custom payload that carries both a file size and a word count.
 * Demonstrates how a node can produce a structured result object.
 */
public record HelloWorldPayload(long fileSize, long wordCount) implements Payload {

	public static HelloWorldPayload of(long fileSize, long wordCount) {
		return new HelloWorldPayload(fileSize, wordCount);
	}
}
