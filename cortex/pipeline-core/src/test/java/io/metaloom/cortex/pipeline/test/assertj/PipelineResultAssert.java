package io.metaloom.cortex.pipeline.test.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.api.PipelineResult;

/**
 * AssertJ asserter for {@link PipelineResult}. Provides fluent assertions for
 * verifying pipeline execution outcomes, individual node results, and output data.
 */
public class PipelineResultAssert extends AbstractAssert<PipelineResultAssert, PipelineResult> {

	protected PipelineResultAssert(PipelineResult actual) {
		super(actual, PipelineResultAssert.class);
	}

	/**
	 * Assert that the pipeline execution succeeded (all nodes completed or skipped).
	 */
	public PipelineResultAssert isSuccess() {
		isNotNull();
		if (!actual.isSuccess()) {
			failWithMessage("Expected pipeline <%s> to succeed but it failed.\nNode results: %s",
					actual.getPipelineName(), actual.getNodeResults());
		}
		return this;
	}

	/**
	 * Assert that the pipeline execution failed (at least one node failed).
	 */
	public PipelineResultAssert isFailed() {
		isNotNull();
		if (actual.isSuccess()) {
			failWithMessage("Expected pipeline <%s> to fail but it succeeded.", actual.getPipelineName());
		}
		return this;
	}

	/**
	 * Assert that the pipeline was executed in dry-run mode.
	 */
	public PipelineResultAssert isDryRun() {
		isNotNull();
		if (!actual.isDryRun()) {
			failWithMessage("Expected pipeline <%s> to be in dry-run mode.", actual.getPipelineName());
		}
		return this;
	}

	/**
	 * Assert that exactly {@code count} nodes participated in the pipeline.
	 */
	public PipelineResultAssert hasNodeCount(int count) {
		isNotNull();
		int actualCount = actual.getNodeResults().size();
		if (actualCount != count) {
			failWithMessage("Expected <%d> node results but found <%d>: %s",
					count, actualCount, actual.getNodeResults().keySet());
		}
		return this;
	}

	/**
	 * Assert that a node with the given id completed successfully.
	 */
	public PipelineResultAssert hasCompletedNode(String nodeId) {
		isNotNull();
		NodeResult nodeResult = actual.getNodeResults().get(nodeId);
		if (nodeResult == null) {
			failWithMessage("Expected node <%s> to be present but it was not. Available: %s",
					nodeId, actual.getNodeResults().keySet());
		} else if (nodeResult.getState() != NodeState.COMPLETED) {
			failWithMessage("Expected node <%s> to be COMPLETED but was <%s>",
					nodeId, nodeResult.getState());
		}
		return this;
	}

	/**
	 * Assert that a node with the given id was skipped.
	 */
	public PipelineResultAssert hasSkippedNode(String nodeId) {
		isNotNull();
		NodeResult nodeResult = actual.getNodeResults().get(nodeId);
		if (nodeResult == null) {
			failWithMessage("Expected node <%s> to be present but it was not. Available: %s",
					nodeId, actual.getNodeResults().keySet());
		} else if (nodeResult.getState() != NodeState.SKIPPED) {
			failWithMessage("Expected node <%s> to be SKIPPED but was <%s>",
					nodeId, nodeResult.getState());
		}
		return this;
	}

	/**
	 * Assert that a node produced a specific output value.
	 */
	public PipelineResultAssert hasNodeOutput(String nodeId, String outputKey, Object expectedValue) {
		isNotNull();
		NodeResult nodeResult = actual.getNodeResults().get(nodeId);
		if (nodeResult == null) {
			failWithMessage("Expected node <%s> to be present but it was not. Available: %s",
					nodeId, actual.getNodeResults().keySet());
			return this;
		}
		Object actualValue = nodeResult.getOutput(outputKey);
		if (actualValue == null) {
			failWithMessage("Expected node <%s> to have output <%s> but it was null. Available outputs: %s",
					nodeId, outputKey, nodeResult.getOutput().keySet());
		} else if (!expectedValue.equals(actualValue)) {
			failWithMessage("Expected node <%s> output <%s> to be <%s> but was <%s>",
					nodeId, outputKey, expectedValue, actualValue);
		}
		return this;
	}

	/**
	 * Assert that a node produced an output with the given key (any value).
	 */
	public PipelineResultAssert hasNodeOutputKey(String nodeId, String outputKey) {
		isNotNull();
		NodeResult nodeResult = actual.getNodeResults().get(nodeId);
		if (nodeResult == null) {
			failWithMessage("Expected node <%s> to be present but it was not. Available: %s",
					nodeId, actual.getNodeResults().keySet());
			return this;
		}
		if (!nodeResult.getOutput().containsKey(outputKey)) {
			failWithMessage("Expected node <%s> to have output key <%s> but it was absent. Available: %s",
					nodeId, outputKey, nodeResult.getOutput().keySet());
		}
		return this;
	}

	/**
	 * Extract the node result for further assertions. Returns a {@link PipelineNodeResultAssert}.
	 */
	public PipelineNodeResultAssert node(String nodeId) {
		isNotNull();
		NodeResult nodeResult = actual.getNodeResults().get(nodeId);
		if (nodeResult == null) {
			failWithMessage("Expected node <%s> to be present but it was not. Available: %s",
					nodeId, actual.getNodeResults().keySet());
		}
		return new PipelineNodeResultAssert(nodeResult);
	}
}
