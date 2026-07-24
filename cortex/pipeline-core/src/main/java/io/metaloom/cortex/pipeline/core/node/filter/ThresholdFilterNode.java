package io.metaloom.cortex.pipeline.core.node.filter;

import java.util.Map;
import java.util.Set;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;

/**
 * Generic filter that checks a single numeric output from an upstream node against
 * a threshold using a configurable comparison operator.
 *
 * <p>Useful for building ad-hoc branch conditions without writing a custom filter,
 * e.g. face detection confidence &gt; 0.8, LLM classification score &gt; 0.5, etc.</p>
 */
public class ThresholdFilterNode extends AbstractFilterNode {

	/**
	 * Comparison operator for threshold checks.
	 */
	public enum Operator {
		GT, GTE, LT, LTE, EQ
	}

	private final String upstreamNodeId;
	private final String outputKey;
	private final Operator operator;
	private final double threshold;

	private ThresholdFilterNode(Builder builder) {
		super(builder.id, builder.name);
		this.upstreamNodeId = builder.upstreamNodeId;
		this.outputKey = builder.outputKey;
		this.operator = builder.operator;
		this.threshold = builder.threshold;
	}

	@Override
	protected boolean evaluate(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		if (upstreamResults == null) {
			return true; // no data to compare — pass
		}
		NodeResult nr = upstreamResults.get(upstreamNodeId);
		if (nr == null || nr.getOutput() == null) {
			return true; // upstream not available — pass
		}
		Object val = nr.getOutput().get(outputKey);
		if (!(val instanceof Number)) {
			return true; // not a number — pass
		}
		double value = ((Number) val).doubleValue();
		return compare(value);
	}

	private boolean compare(double value) {
		switch (operator) {
			case GT:
				return value > threshold;
			case GTE:
				return value >= threshold;
			case LT:
				return value < threshold;
			case LTE:
				return value <= threshold;
			case EQ:
				return Double.compare(value, threshold) == 0;
			default:
				return true;
		}
	}

	@Override
	protected String rejectReason(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		return upstreamNodeId + ":" + outputKey + " failed " + operator + " " + threshold;
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;
		private Set<String> dependencies = Set.of();
		private String upstreamNodeId;
		private String outputKey;
		private Operator operator;
		private double threshold;

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

		/** The upstream node id to read the numeric value from. */
		public Builder upstreamNodeId(String upstreamNodeId) {
			this.upstreamNodeId = upstreamNodeId;
			return this;
		}

		/** The key in the upstream node's output map containing the numeric value. */
		public Builder outputKey(String outputKey) {
			this.outputKey = outputKey;
			return this;
		}

		/** The comparison operator. */
		public Builder operator(Operator operator) {
			this.operator = operator;
			return this;
		}

		/** The threshold value to compare against. */
		public Builder threshold(double threshold) {
			this.threshold = threshold;
			return this;
		}

		public ThresholdFilterNode build() {
			if (upstreamNodeId == null || outputKey == null || operator == null) {
				throw new IllegalStateException("upstreamNodeId, outputKey, and operator are required");
			}
			return new ThresholdFilterNode(this);
		}
	}
}
