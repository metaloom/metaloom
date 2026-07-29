package io.metaloom.cortex.pipeline.core.node.filter;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

/**
 * Generic filter that checks a single numeric output from an upstream node against
 * a threshold using a configurable comparison operator.
 *
 * <p>Useful for building ad-hoc branch conditions without writing a custom filter,
 * e.g. face detection confidence &gt; 0.8, LLM classification score &gt; 0.5, etc.</p>
 *
 * <p>The value arrives on a declared port. It used to be addressed as
 * {@code upstreamNodeId + outputKey} - two builder options naming a node the pipeline author
 * was free to rename, at which point the filter silently passed everything.</p>
 */
public class ThresholdFilterNode extends AbstractFilterNode {

	/**
	 * Comparison operator for threshold checks.
	 */
	public enum Operator {
		GT, GTE, LT, LTE, EQ
	}

	/** The number to test. Unwired means "nothing to compare", which passes. */
	public static final InputPort<Double> IN_VALUE = InputPort.one("value", ContentTypeRegistry.SCALAR_NUMBER, Double.class);

	private final Operator operator;
	private final double threshold;

	private ThresholdFilterNode(Builder builder) {
		super(builder.id, builder.name);
		this.operator = builder.operator;
		this.threshold = builder.threshold;
	}

	@Override
	protected boolean evaluate(NodeContext<LoomMedia> ctx) {
		Double value = ctx.input(IN_VALUE);
		if (value == null) {
			return true; // no data to compare — pass
		}
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
	protected String rejectReason(NodeContext<LoomMedia> ctx) {
		return IN_VALUE.id() + " failed " + operator + " " + threshold;
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;
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
			if (operator == null) {
				throw new IllegalStateException("An operator is required");
			}
			return new ThresholdFilterNode(this);
		}
	}
}
