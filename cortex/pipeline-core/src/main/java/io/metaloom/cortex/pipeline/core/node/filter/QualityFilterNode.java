package io.metaloom.cortex.pipeline.core.node.filter;

import java.util.Map;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.vertx.core.json.JsonObject;

/**
 * Filters media based on quality metrics produced by an upstream quality node.
 * Checks blurriness, resolution, and the quality flag.
 *
 * <p>All thresholds are optional. An item passes only if all configured checks pass.</p>
 */
public class QualityFilterNode extends AbstractFilterNode {

	/** The metric set produced by a quality node, as one structured payload. */
	public static final InputPort<String> IN_QUALITY = InputPort.one("quality", ContentTypeRegistry.STRUCT_QUALITY, String.class);

	private final Double maxBlurriness;
	private final Integer minWidth;
	private final Integer minHeight;
	private final boolean requireQualityFlag;

	private QualityFilterNode(Builder builder) {
		super(builder.id, builder.name);
		this.maxBlurriness = builder.maxBlurriness;
		this.minWidth = builder.minWidth;
		this.minHeight = builder.minHeight;
		this.requireQualityFlag = builder.requireQualityFlag;
	}

	@Override
	protected boolean evaluate(NodeContext<LoomMedia> ctx) {
		Map<String, Object> outputs = metrics(ctx);
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

		// Resolution check - one pair for both image and video, matching the quality node's ports
		Integer width = getInteger(outputs, "width");
		Integer height = getInteger(outputs, "height");
		if (minWidth != null && width != null && width < minWidth) {
			return false;
		}
		if (minHeight != null && height != null && height < minHeight) {
			return false;
		}

		// Quality flag check
		if (requireQualityFlag) {
			Object flag = outputs.get("flag");
			if (!"SUCCESS".equals(flag)) {
				return false;
			}
		}

		return true;
	}

	@Override
	protected String rejectReason(NodeContext<LoomMedia> ctx) {
		return "quality below threshold";
	}

	private Map<String, Object> metrics(NodeContext<LoomMedia> ctx) {
		String encoded = ctx.input(IN_QUALITY);
		return encoded == null ? Map.of() : new JsonObject(encoded).getMap();
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
