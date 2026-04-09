package io.metaloom.cortex.api.node.payload;

import java.nio.file.Path;

/**
 * Payload representing an image. Produced by thumbnail generators, frame extractors,
 * or any node that outputs image data.
 */
public interface ImagePayload extends Payload {

	/**
	 * Path to the image file.
	 */
	Path path();

	/**
	 * Image width in pixels.
	 */
	int width();

	/**
	 * Image height in pixels.
	 */
	int height();

	/**
	 * Image format (e.g. "png", "jpg", "webp").
	 */
	String format();

	record Default(Path path, int width, int height, String format) implements ImagePayload {
	}

	static ImagePayload of(Path path, int width, int height, String format) {
		return new Default(path, width, height, format);
	}
}
