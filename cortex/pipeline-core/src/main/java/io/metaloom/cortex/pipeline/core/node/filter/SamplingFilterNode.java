package io.metaloom.cortex.pipeline.core.node.filter;

import java.util.Map;
import java.util.Random;
import java.util.Set;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;

/**
 * Randomly passes a configured percentage of items. Items that do not pass
 * are rejected. Useful for A/B testing pipeline branches, validation sampling,
 * or load testing.
 */
public class SamplingFilterNode extends AbstractFilterNode {

	private final double passRate;
	private final Random random;

	private SamplingFilterNode(Builder builder) {
		super(builder.id, builder.name);
		this.passRate = builder.passRate;
		this.random = builder.seed != null ? new Random(builder.seed) : new Random();
	}

	@Override
	protected boolean evaluate(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		return random.nextDouble() < passRate;
	}

	@Override
	protected String rejectReason(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		return "rejected by sampling (rate=" + passRate + ")";
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;
		private Set<String> dependencies = Set.of();
		private double passRate = 0.1;
		private Long seed;

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

		/** Fraction of items to pass through (0.0 = none, 1.0 = all). */
		public Builder passRate(double passRate) {
			if (passRate < 0.0 || passRate > 1.0) {
				throw new IllegalArgumentException("passRate must be between 0.0 and 1.0");
			}
			this.passRate = passRate;
			return this;
		}

		/** Optional seed for reproducible sampling. */
		public Builder seed(long seed) {
			this.seed = seed;
			return this;
		}

		public SamplingFilterNode build() {
			return new SamplingFilterNode(this);
		}
	}
}
