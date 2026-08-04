package io.metaloom.loom.pipeline.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A small, disposable rendering of what one output port carried, so a human can look at it.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>
 * A port carrying produced media does not carry the media. {@code ThumbnailNode} and
 * {@code ImageManipulationNode} both emit an {@code artifact/image} port whose value is an
 * <em>absolute path on the worker that produced it</em>. Loom has no route to those bytes and no way
 * to reach that filesystem, so an operator debugging a pipeline can be told the name of a file they
 * cannot look at. This is the channel that closes that gap.
 * </p>
 *
 * <h2>What it is not</h2>
 *
 * <p>
 * Not the artifact. A preview is deliberately lossy, capped, and pruned with the run it belongs to
 * — run-scoped diagnostics, not catalogue state. Promoting a node's output into something durable
 * and addressable is a different feature with different retention, and it is not this one.
 * </p>
 *
 * <p>
 * It is also <strong>opt-in per run</strong> ({@link NodeTask#isCapturePreviews()}). A production run
 * over 100 000 files must not pay to encode and store a hundred thousand thumbnails nobody asked
 * for, so a run that did not request previews produces none and the whole path costs one boolean
 * check.
 * </p>
 */
public class NodePreview {

	/**
	 * Longest edge, in pixels, of a generated preview.
	 *
	 * <p>
	 * Sized for a node card and a modal on a normal display, not for inspection at full resolution.
	 * Someone who needs the real pixels needs the artifact, which is a different feature.
	 * </p>
	 */
	public static final int MAX_EDGE_PX = 512;

	/**
	 * Hard ceiling on the encoded bytes of one preview.
	 *
	 * <p>
	 * Previews are stored inline in {@code pipeline_node_task.previews}, so this bounds a row rather
	 * than a file. A preview that would exceed it is <strong>dropped, never truncated</strong>: half a
	 * JPEG is not a smaller JPEG, it is a broken one, and a broken image in a debugging view is worse
	 * than an honest absence.
	 * </p>
	 */
	public static final int DEFAULT_MAX_BYTES = 96 * 1024;

	private final String mimeType;
	private final int width;
	private final int height;
	private final byte[] data;
	private final String markdown;
	private final String skippedReason;

	@JsonCreator
	public NodePreview(@JsonProperty("mimeType") String mimeType, @JsonProperty("width") int width,
		@JsonProperty("height") int height, @JsonProperty("data") byte[] data,
		@JsonProperty("markdown") String markdown,
		@JsonProperty("skippedReason") String skippedReason) {
		this.mimeType = mimeType;
		this.width = width;
		this.height = height;
		this.data = data;
		this.markdown = markdown;
		this.skippedReason = skippedReason;
	}

	/** A preview that carries bytes. */
	public static NodePreview image(String mimeType, int width, int height, byte[] data) {
		return new NodePreview(Objects.requireNonNull(mimeType, "A mime type must be set"), width, height,
			Objects.requireNonNull(data, "Preview data must be set"), null, null);
	}

	/**
	 * A preview a node wrote for itself, as Markdown.
	 *
	 * <p>
	 * The escape hatch from the content-type defaults. Those defaults switch on the port's declared
	 * family and are right for most ports, but a node knows things about its own output that the type
	 * system does not - that these three numbers are a bounding box, that this array is one row per
	 * detected face. A GFM table says that; {@code {"box":[10,20,64,64],"score":0.93}} does not.
	 * </p>
	 *
	 * <p>
	 * Nothing is required of a node for the defaults to work; this only ever improves on them.
	 * </p>
	 */
	public static NodePreview markdown(String markdown) {
		return new NodePreview(null, 0, 0, null, Objects.requireNonNull(markdown, "Markdown must be set"), null);
	}

	/**
	 * A preview that could not be produced, and why.
	 *
	 * <p>
	 * Recorded rather than omitted so the UI can say "too large to preview" instead of leaving a port
	 * looking as though it emitted nothing — which is a different and much more alarming thing.
	 * </p>
	 */
	public static NodePreview skipped(String reason) {
		return new NodePreview(null, 0, 0, null, null, reason);
	}

	public String getMimeType() {
		return mimeType;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	/** @return the encoded image bytes, or null when this preview was skipped */
	public byte[] getData() {
		return data;
	}

	/** @return why no bytes were produced, or null when they were */
	public String getSkippedReason() {
		return skippedReason;
	}

	/** @return the node's own Markdown rendering of this port, or null when it wrote none */
	public String getMarkdown() {
		return markdown;
	}

	@JsonIgnore
	public boolean hasData() {
		return data != null && data.length > 0;
	}

	@JsonIgnore
	public boolean hasMarkdown() {
		return markdown != null && !markdown.isBlank();
	}

	/**
	 * Fold a node-authored Markdown preview onto a generated image one, so a port can have both.
	 *
	 * <p>
	 * A node that describes its output <em>and</em> produced an image should not have to choose which
	 * one the reader gets.
	 * </p>
	 */
	public NodePreview withMarkdown(String markdown) {
		return new NodePreview(mimeType, width, height, data, markdown, skippedReason);
	}

	@Override
	public String toString() {
		if (hasData()) {
			return "NodePreview[" + mimeType + " " + width + "x" + height + " " + data.length + "B]";
		}
		if (hasMarkdown()) {
			return "NodePreview[markdown " + markdown.length() + " chars]";
		}
		return "NodePreview[skipped: " + skippedReason + "]";
	}
}
