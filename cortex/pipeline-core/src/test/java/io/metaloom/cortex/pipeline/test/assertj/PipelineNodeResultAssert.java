package io.metaloom.cortex.pipeline.test.assertj;

import org.assertj.core.api.AbstractAssert;

import java.util.List;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
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

	public PipelineNodeResultAssert hasOutput(OutputPort<?> port) {
		isNotNull();
		if (!actual.has(port)) {
			failWithMessage("Expected an emission on port <%s> but there was none. Ports emitted: %s",
					port.id(), actual.getOutputs().keySet());
		}
		return this;
	}

	public <T> PipelineNodeResultAssert hasOutput(OutputPort<T> port, T expectedValue) {
		isNotNull();
		Object actualValue = actual.get(port);
		if (actualValue == null) {
			failWithMessage("Expected port <%s> to carry <%s> but it carried nothing. Ports emitted: %s",
					port.id(), expectedValue, actual.getOutputs().keySet());
		} else if (!expectedValue.equals(actualValue)) {
			failWithMessage("Expected port <%s> to carry <%s> but it carried <%s>",
					port.id(), expectedValue, actualValue);
		}
		return this;
	}

	/** Assert the elements of a {@code MANY} port, in order. */
	public <T> PipelineNodeResultAssert hasElements(OutputPort<T> port, List<T> expected) {
		isNotNull();
		List<T> actualElements = actual.elements(port);
		if (!expected.equals(actualElements)) {
			failWithMessage("Expected port <%s> to carry <%s> but it carried <%s>",
					port.id(), expected, actualElements);
		}
		return this;
	}

	public PipelineNodeResultAssert hasOutputCount(int count) {
		isNotNull();
		int actualCount = actual.getOutputs().size();
		if (actualCount != count) {
			failWithMessage("Expected <%d> output ports but found <%d>: %s",
					count, actualCount, actual.getOutputs().keySet());
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
