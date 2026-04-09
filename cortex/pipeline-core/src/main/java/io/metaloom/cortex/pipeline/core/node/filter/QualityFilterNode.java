package io.metaloom.cortex.pipeline.core.node.filter;

import java.util.Map;
import java.util.Set;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;

/**
 * Filters media based on quality metrics produced by an upstream quality node.
 * Checks blurriness, resolution, and the quality flag.
 *
 * <p>All thresholds are optional. An item passes only if all configured checks pass.</p>
 */
public class QualityFilterNode extends AbstractFilterNode {

	private final String qualityNodeId;
	private final Double maxBlurriness;
	private final Integer minWidth;
	private final Integer minHeight;
	private final boolean requireQualityFlag;

	private QualityFilterNode(Builder builder) {
		super(builder.id, builder.name);
		this.qualityNodeId = builder.qualityNodeId;
		this.maxBlurriness = builder.maxBlurriness;
		this.minWidth = builder.minWidth;
		this.minHeight = builder.minHeight;
		this.requireQualityFlag = builder.requireQualityFlag;
	}

	@Override
	protected boolean evaluate(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		Map<String, Object> outputs = getQualityOutputs(upstreamResults);
		if (outputs.isEmpty()) {
			// No quality data available — pass by default
			return true;
		}

		// Blurriness check
		if (maxBlurriness != null) {
			Double blurriness = getDouble(outputs, "blurriness");
			if (blurriness != null && blurriness > maxBlurriness) {
				return false;
			}
		}

		// Resolution check (image or video)
		Integer width = getInteger(outputs, "image_width");
		if (width == null) {
			width = getInteger(outputs, "video_width");
		}
		Integer height = getInteger(outputs, "image_height");
		if (height == null) {
			height = getInteger(outputs, "video_height");
		}
		if (minWidth != null && width != null && width < minWidth) {
			return false;
		}
		if (minHeight != null && height != null && height < minHeight) {
			return false;
		}

		// Quality flag check
		if (requireQualityFlag) {
			Object flag = outputs.get("quality_flag");
			if (!"SUCCESS".equals(flag)) {
				return false;
			}
		}

		return true;
	}

	@Override
	protected String rejectReason(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		return "quality below threshold";
	}

	private Map<String, Object> getQualityOutputs(Map<String, NodeResult> upstreamResults) {
		if (qualityNodeId != null && upstreamResults != null) {
			NodeResult qr = upstreamResults.get(qualityNodeId);
			if (qr != null && qr.getOutput() != null) {
				return qr.getOutput();
			}
		}
		return Map.of();
	}

	private Integer getInteger(Map<String, Object> map, String key) {
		Object val = map.get(key);
		return val instanceof Number ? ((Number) val).intValue() : null;
	}

	private Double getDouble(Map<String, Object> map, String key) {
		Object val = map.get(key);
		return val instanceof Number ? ((Number) val).doubleValue() : null;
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;
		private Set<String> dependencies = Set.of();
		private String qualityNodeId;
		private Double maxBlurriness;
		private Integer minWidth;
		private Integer minHeight;
		private boolean requireQualityFlag;

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

		/** ID of the upstream quality node to read outputs from. */
		public Builder qualityNodeId(String qualityNodeId) {
			this.qualityNodeId = qualityNodeId;
			return this;
		}

		/** Reject if blurriness exceeds this value. */
		public Builder maxBlurriness(Double maxBlurriness) {
			this.maxBlurriness = maxBlurriness;
			return this;
		}

		/** Reject if image/video width is below this value. */
		public Builder minWidth(Integer minWidth) {
			this.minWidth = minWidth;
			return this;
		}

		/** Reject if image/video height is below this value. */
		public Builder minHeight(Integer minHeight) {
			this.minHeight = minHeight;
			return this;
		}

		/** Reject if the quality node did not flag SUCCESS. */
		public Builder requireQualityFlag(boolean requireQualityFlag) {
			this.requireQualityFlag = requireQualityFlag;
			return this;
		}

		public QualityFilterNode build() {
			return new QualityFilterNode(this);
		}
	}
}
