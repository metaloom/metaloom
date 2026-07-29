package io.metaloom.cortex.pipeline.core.node.filter;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.vertx.core.json.JsonObject;

/**
 * Filters media based on intrinsic file and media attributes such as file size,
 * image/video resolution, audio channel count, bitrate, duration, and FPS.
 *
 * <p>When upstream results from a quality node are available, the filter reads
 * resolution, FPS, and similar attributes from those outputs. Otherwise it
 * falls back to the file-level {@link LoomMedia#size()} check only.</p>
 *
 * <p>All thresholds are optional — set to {@code null} to skip a check.
 * An item passes only if <em>all</em> configured checks pass.</p>
 */
public class AssetAttributeFilterNode extends AbstractFilterNode {

	private static final Logger log = LoggerFactory.getLogger(AssetAttributeFilterNode.class);

	/** Optional metric set from a quality node; without it only the file-size checks apply. */
	public static final InputPort<String> IN_QUALITY = InputPort.one("quality", ContentTypeRegistry.STRUCT_QUALITY, String.class);

	private final Long minFileSize;
	private final Long maxFileSize;
	private final Integer minWidth;
	private final Integer maxWidth;
	private final Integer minHeight;
	private final Integer maxHeight;
	private final Integer minAudioChannels;
	private final Integer maxAudioChannels;
	private final Long minBitrate;
	private final Long maxBitrate;
	private final Double minDurationSeconds;
	private final Double maxDurationSeconds;
	private final Double minFps;
	private final Double maxFps;

	private AssetAttributeFilterNode(Builder builder) {
		super(builder.id, builder.name, builder.concurrency);
		this.minFileSize = builder.minFileSize;
		this.maxFileSize = builder.maxFileSize;
		this.minWidth = builder.minWidth;
		this.maxWidth = builder.maxWidth;
		this.minHeight = builder.minHeight;
		this.maxHeight = builder.maxHeight;
		this.minAudioChannels = builder.minAudioChannels;
		this.maxAudioChannels = builder.maxAudioChannels;
		this.minBitrate = builder.minBitrate;
		this.maxBitrate = builder.maxBitrate;
		this.minDurationSeconds = builder.minDurationSeconds;
		this.maxDurationSeconds = builder.maxDurationSeconds;
		this.minFps = builder.minFps;
		this.maxFps = builder.maxFps;
	}

	@Override
	protected boolean evaluate(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		// File size check (always available)
		if (minFileSize != null || maxFileSize != null) {
			try {
				long size = media.size();
				if (minFileSize != null && size < minFileSize) {
					return false;
				}
				if (maxFileSize != null && size > maxFileSize) {
					return false;
				}
			} catch (IOException e) {
				log.warn("Could not read file size for {}: {}", media.absolutePath(), e.getMessage());
				return false;
			}
		}

		// Upstream quality outputs
		Map<String, Object> qualityOutputs = metrics(ctx);

		// Resolution checks (image or video)
		Integer width = resolveWidth(qualityOutputs, media);
		Integer height = resolveHeight(qualityOutputs, media);
		if (width != null) {
			if (minWidth != null && width < minWidth) return false;
			if (maxWidth != null && width > maxWidth) return false;
		}
		if (height != null) {
			if (minHeight != null && height < minHeight) return false;
			if (maxHeight != null && height > maxHeight) return false;
		}

		// FPS check
		if (minFps != null || maxFps != null) {
			Double fps = getDouble(qualityOutputs, "fps");
			if (fps != null) {
				if (minFps != null && fps < minFps) return false;
				if (maxFps != null && fps > maxFps) return false;
			}
		}

		// Audio channel count (from upstream outputs)
		if (minAudioChannels != null || maxAudioChannels != null) {
			Integer channels = getInteger(qualityOutputs, "audio_channels");
			if (channels != null) {
				if (minAudioChannels != null && channels < minAudioChannels) return false;
				if (maxAudioChannels != null && channels > maxAudioChannels) return false;
			}
		}

		// Bitrate
		if (minBitrate != null || maxBitrate != null) {
			Long bitrate = getLong(qualityOutputs, "bitrate");
			if (bitrate != null) {
				if (minBitrate != null && bitrate < minBitrate) return false;
				if (maxBitrate != null && bitrate > maxBitrate) return false;
			}
		}

		// Duration
		if (minDurationSeconds != null || maxDurationSeconds != null) {
			Double fps = getDouble(qualityOutputs, "fps");
			Integer frameCount = getInteger(qualityOutputs, "frame_count");
			if (fps != null && frameCount != null && fps > 0) {
				double durationSec = frameCount / fps;
				if (minDurationSeconds != null && durationSec < minDurationSeconds) return false;
				if (maxDurationSeconds != null && durationSec > maxDurationSeconds) return false;
			}
		}

		return true;
	}

	@Override
	protected String rejectReason(NodeContext<LoomMedia> ctx) {
		return "asset attributes out of range";
	}

	private Map<String, Object> metrics(NodeContext<LoomMedia> ctx) {
		String encoded = ctx.input(IN_QUALITY);
		return encoded == null ? Map.of() : new JsonObject(encoded).getMap();
	}

	private Integer resolveWidth(Map<String, Object> qualityOutputs, LoomMedia media) {
		return getInteger(qualityOutputs, "width");
	}

	private Integer resolveHeight(Map<String, Object> qualityOutputs, LoomMedia media) {
		return getInteger(qualityOutputs, "height");
	}

	private Integer getInteger(Map<String, Object> map, String key) {
		Object val = map.get(key);
		if (val instanceof Number) {
			return ((Number) val).intValue();
		}
		return null;
	}

	private Long getLong(Map<String, Object> map, String key) {
		Object val = map.get(key);
		if (val instanceof Number) {
			return ((Number) val).longValue();
		}
		return null;
	}

	private Double getDouble(Map<String, Object> map, String key) {
		Object val = map.get(key);
		if (val instanceof Number) {
			return ((Number) val).doubleValue();
		}
		return null;
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;
		private int concurrency = 1;
		private Long minFileSize;
		private Long maxFileSize;
		private Integer minWidth;
		private Integer maxWidth;
		private Integer minHeight;
		private Integer maxHeight;
		private Integer minAudioChannels;
		private Integer maxAudioChannels;
		private Long minBitrate;
		private Long maxBitrate;
		private Double minDurationSeconds;
		private Double maxDurationSeconds;
		private Double minFps;
		private Double maxFps;

		private Builder(String id) {
			this.id = id;
			this.name = id;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder concurrency(int concurrency) {
			this.concurrency = concurrency;
			return this;
		}

		public Builder minFileSize(Long minFileSize) {
			this.minFileSize = minFileSize;
			return this;
		}

		public Builder maxFileSize(Long maxFileSize) {
			this.maxFileSize = maxFileSize;
			return this;
		}

		public Builder minWidth(Integer minWidth) {
			this.minWidth = minWidth;
			return this;
		}

		public Builder maxWidth(Integer maxWidth) {
			this.maxWidth = maxWidth;
			return this;
		}

		public Builder minHeight(Integer minHeight) {
			this.minHeight = minHeight;
			return this;
		}

		public Builder maxHeight(Integer maxHeight) {
			this.maxHeight = maxHeight;
			return this;
		}

		public Builder minAudioChannels(Integer minAudioChannels) {
			this.minAudioChannels = minAudioChannels;
			return this;
		}

		public Builder maxAudioChannels(Integer maxAudioChannels) {
			this.maxAudioChannels = maxAudioChannels;
			return this;
		}

		public Builder minBitrate(Long minBitrate) {
			this.minBitrate = minBitrate;
			return this;
		}

		public Builder maxBitrate(Long maxBitrate) {
			this.maxBitrate = maxBitrate;
			return this;
		}

		public Builder minDurationSeconds(Double minDurationSeconds) {
			this.minDurationSeconds = minDurationSeconds;
			return this;
		}

		public Builder maxDurationSeconds(Double maxDurationSeconds) {
			this.maxDurationSeconds = maxDurationSeconds;
			return this;
		}

		public Builder minFps(Double minFps) {
			this.minFps = minFps;
			return this;
		}

		public Builder maxFps(Double maxFps) {
			this.maxFps = maxFps;
			return this;
		}

		public AssetAttributeFilterNode build() {
			return new AssetAttributeFilterNode(this);
		}
	}
}
