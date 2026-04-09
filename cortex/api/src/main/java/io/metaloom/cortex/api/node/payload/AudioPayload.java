package io.metaloom.cortex.api.node.payload;

import java.nio.file.Path;

/**
 * Payload representing an audio stream. Produced when extracting audio from a video asset
 * (e.g. for Whisper speech-to-text processing).
 */
public interface AudioPayload extends Payload {

	/**
	 * Path to the extracted audio file.
	 */
	Path path();

	/**
	 * Audio format (e.g. "wav", "mp3", "ogg").
	 */
	String format();

	/**
	 * Duration in milliseconds, or -1 if unknown.
	 */
	long durationMs();

	record Default(Path path, String format, long durationMs) implements AudioPayload {
	}

	static AudioPayload of(Path path, String format, long durationMs) {
		return new Default(path, format, durationMs);
	}

	static AudioPayload of(Path path, String format) {
		return new Default(path, format, -1);
	}
}
