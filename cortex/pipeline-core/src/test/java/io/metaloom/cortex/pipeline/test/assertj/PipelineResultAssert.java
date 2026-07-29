package io.metaloom.cortex.pipeline.test.assertj;

import org.assertj.core.api.AbstractAssert;

import java.util.List;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
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
		} else if (nodeResult.getState() != ResultState.SUCCESS) {
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
		} else if (nodeResult.getState() != ResultState.SKIPPED) {
			failWithMessage("Expected node <%s> to be SKIPPED but was <%s>",
					nodeId, nodeResult.getState());
		}
		return this;
	}

	/**
	 * Assert that a node emitted a specific value on a {@code ONE} output port.
	 *
	 * <p>
	 * Takes the {@link OutputPort} rather than a key string on purpose: an assertion
	 * that names a port the node no longer declares is now a compile error instead of
	 * a test that quietly checks nothing.
	 * </p>
	 */
	public <T> PipelineResultAssert hasNodeOutput(String nodeId, OutputPort<T> port, T expectedValue) {
		isNotNull();
		NodeResult nodeResult = nodeResult(nodeId);
		if (nodeResult == null) {
			return this;
		}
		Object actualValue = nodeResult.get(port);
		if (actualValue == null) {
			failWithMessage("Expected node <%s> to emit on port <%s> but it did not. Ports emitted: %s",
					nodeId, port.id(), nodeResult.getOutputs().keySet());
		} else if (!expectedValue.equals(actualValue)) {
			failWithMessage("Expected node <%s> port <%s> to carry <%s> but it carried <%s>",
					nodeId, port.id(), expectedValue, actualValue);
		}
		return this;
	}

	/**
	 * Assert that a node emitted on the given port (any value).
	 */
	public PipelineResultAssert hasNodeOutputPort(String nodeId, OutputPort<?> port) {
		isNotNull();
		NodeResult nodeResult = nodeResult(nodeId);
		if (nodeResult == null) {
			return this;
		}
		if (!nodeResult.has(port)) {
			failWithMessage("Expected node <%s> to emit on port <%s> but it did not. Ports emitted: %s",
					nodeId, port.id(), nodeResult.getOutputs().keySet());
		}
		return this;
	}

	/**
	 * Assert the elements a node emitted on a {@code MANY} output port, in order.
	 */
	public <T> PipelineResultAssert hasNodeElements(String nodeId, OutputPort<T> port, List<T> expected) {
		isNotNull();
		NodeResult nodeResult = nodeResult(nodeId);
		if (nodeResult == null) {
			return this;
		}
		List<T> actualElements = nodeResult.elements(port);
		if (!expected.equals(actualElements)) {
			failWithMessage("Expected node <%s> port <%s> to carry <%s> but it carried <%s>",
					nodeId, port.id(), expected, actualElements);
		}
		return this;
	}

	private NodeResult nodeResult(String nodeId) {
		NodeResult nodeResult = actual.getNodeResults().get(nodeId);
		if (nodeResult == null) {
			failWithMessage("Expected node <%s> to be present but it was not. Available: %s",
					nodeId, actual.getNodeResults().keySet());
		}
		return nodeResult;
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
