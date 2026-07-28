package io.metaloom.cortex.node.sink.s3;

import java.nio.file.Path;

/**
 * One local file the sink intends to upload, with the provenance that produced it.
 *
 * @param sourceNode  the producing node's graph id, or {@link #SOURCE_MEDIA} for the media item
 * @param sourceKey   the output key it came from, or {@link #SOURCE_MEDIA}
 * @param index       position within a multi-valued output; 0 for a single value
 * @param multiValued whether the source output held a list
 * @param file        the local file, absolute and normalised
 * @param present     whether {@code file} exists on this worker
 */
public record SinkArtifact(String sourceNode, String sourceKey, int index, boolean multiValued,
	Path file, boolean present) {

	/** Marker used for both node and key when the artifact is the media item itself. */
	public static final String SOURCE_MEDIA = "media";

	/**
	 * @return the file's extension including the dot, or an empty string
	 */
	public String extension() {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot);
	}

	/**
	 * @return the file name without its extension
	 */
	public String baseName() {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot <= 0 ? name : name.substring(0, dot);
	}

	/**
	 * @return the file name
	 */
	public String fileName() {
		return file.getFileName().toString();
	}

	/**
	 * @return a short {@code node:key[index]} label for log and error messages
	 */
	public String describe() {
		return sourceNode + ":" + sourceKey + (multiValued ? "[" + index + "]" : "");
	}
}
