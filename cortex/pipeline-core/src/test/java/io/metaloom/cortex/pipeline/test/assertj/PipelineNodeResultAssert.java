package io.metaloom.cortex.pipeline.test.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;

/**
 * AssertJ asserter for a single pipeline-level {@link NodeResult}.
 * Use via {@link PipelineResultAssert#node(String)}.
 */
public class PipelineNodeResultAssert extends AbstractAssert<PipelineNodeResultAssert, NodeResult> {

	protected PipelineNodeResultAssert(NodeResult actual) {
		super(actual, PipelineNodeResultAssert.class);
	}

	public PipelineNodeResultAssert isCompleted() {
		isNotNull();
		if (actual.getState() != ResultState.SUCCESS) {
			failWithMessage("Expected node <%s> to be COMPLETED but was <%s>",
					actual.getNodeId(), actual.getState());
		}
		return this;
	}

	public PipelineNodeResultAssert isSkipped() {
		isNotNull();
		if (actual.getState() != ResultState.SKIPPED) {
			failWithMessage("Expected node <%s> to be SKIPPED but was <%s>",
					actual.getNodeId(), actual.getState());
		}
		return this;
	}

	public PipelineNodeResultAssert isFailed() {
		isNotNull();
		if (actual.getState() != ResultState.FAILED) {
			failWithMessage("Expected node <%s> to be FAILED but was <%s>",
					actual.getNodeId(), actual.getState());
		}
		return this;
	}

	public PipelineNodeResultAssert hasOutput(String key) {
		isNotNull();
		if (!actual.getOutput().containsKey(key)) {
			failWithMessage("Expected output key <%s> but it was absent. Available: %s",
					key, actual.getOutput().keySet());
		}
		return this;
	}

	public PipelineNodeResultAssert hasOutput(String key, Object expectedValue) {
		isNotNull();
		Object actualValue = actual.getOutput(key);
		if (actualValue == null) {
			failWithMessage("Expected output <%s> to be <%s> but it was null. Available: %s",
					key, expectedValue, actual.getOutput().keySet());
		} else if (!expectedValue.equals(actualValue)) {
			failWithMessage("Expected output <%s> to be <%s> but was <%s>",
					key, expectedValue, actualValue);
		}
		return this;
	}

	public PipelineNodeResultAssert hasOutputCount(int count) {
		isNotNull();
		int actualCount = actual.getOutput().size();
		if (actualCount != count) {
			failWithMessage("Expected <%d> outputs but found <%d>: %s",
					count, actualCount, actual.getOutput().keySet());
		}
		return this;
	}

	public PipelineNodeResultAssert hasDurationGreaterThan(long minMs) {
		isNotNull();
		if (actual.getDurationMs() < minMs) {
			failWithMessage("Expected duration > %dms but was %dms", minMs, actual.getDurationMs());
		}
		return this;
	}
}
