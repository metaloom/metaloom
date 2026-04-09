package io.metaloom.cortex.pipeline.core.node.filter;

import java.util.Map;
import java.util.Set;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;

/**
 * In-pipeline MIME type / media type filter. More granular than the pipeline-level
 * {@link io.metaloom.cortex.pipeline.api.filter.MediaFilter} — useful for branching
 * within a pipeline that handles mixed media types.
 *
 * <p>Supports filtering on the broad media category (video, image, audio, document)
 * and on path-based extension patterns.</p>
 */
public class MimeTypeFilterNode extends AbstractFilterNode {

	private final boolean allowVideo;
	private final boolean allowImage;
	private final boolean allowAudio;
	private final boolean allowDocument;
	private final Set<String> allowedExtensions;

	private MimeTypeFilterNode(Builder builder) {
		super(builder.id, builder.name, builder.dependencies);
		this.allowVideo = builder.allowVideo;
		this.allowImage = builder.allowImage;
		this.allowAudio = builder.allowAudio;
		this.allowDocument = builder.allowDocument;
		this.allowedExtensions = builder.allowedExtensions;
	}

	@Override
	protected boolean evaluate(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		// Check media type category
		boolean categoryMatch = false;
		if (allowVideo && media.isVideo()) categoryMatch = true;
		if (allowImage && media.isImage()) categoryMatch = true;
		if (allowAudio && media.isAudio()) categoryMatch = true;
		if (allowDocument && media.isDocument()) categoryMatch = true;

		// If no category flags are set, treat as "all categories allowed"
		if (!allowVideo && !allowImage && !allowAudio && !allowDocument) {
			categoryMatch = true;
		}

		if (!categoryMatch) {
			return false;
		}

		// Extension check
		if (allowedExtensions != null && !allowedExtensions.isEmpty()) {
			String path = media.absolutePath().toLowerCase();
			int dot = path.lastIndexOf('.');
			if (dot < 0) {
				return false; // no extension
			}
			String ext = path.substring(dot + 1);
			return allowedExtensions.contains(ext);
		}

		return true;
	}

	@Override
	protected String rejectReason(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		return "media type not allowed";
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;
		private Set<String> dependencies = Set.of();
		private boolean allowVideo;
		private boolean allowImage;
		private boolean allowAudio;
		private boolean allowDocument;
		private Set<String> allowedExtensions;

		private Builder(String id) {
			this.id = id;
			this.name = id;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder dependencies(Set<String> dependencies) {
			this.dependencies = dependencies;
			return this;
		}

		public Builder allowVideo(boolean allowVideo) {
			this.allowVideo = allowVideo;
			return this;
		}

		public Builder allowImage(boolean allowImage) {
			this.allowImage = allowImage;
			return this;
		}

		public Builder allowAudio(boolean allowAudio) {
			this.allowAudio = allowAudio;
			return this;
		}

		public Builder allowDocument(boolean allowDocument) {
			this.allowDocument = allowDocument;
			return this;
		}

		/** Only allow files with these extensions (lowercase, no dot). */
		public Builder allowedExtensions(Set<String> allowedExtensions) {
			this.allowedExtensions = allowedExtensions;
			return this;
		}

		public MimeTypeFilterNode build() {
			return new MimeTypeFilterNode(this);
		}
	}
}
