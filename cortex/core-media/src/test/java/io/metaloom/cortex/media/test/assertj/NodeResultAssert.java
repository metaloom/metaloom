package io.metaloom.cortex.media.test.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;

public class NodeResultAssert extends AbstractAssert<NodeResultAssert, NodeResult> {

	protected NodeResultAssert(NodeResult actual) {
		super(actual, NodeResultAssert.class);
	}

	public NodeResultAssert isSuccess() {
		assertEquals(ResultState.SUCCESS, actual.getState(), "The node was not in state success.");
		return this;
	}

	public NodeResultAssert isSkipped() {
		assertEquals(ResultState.SKIPPED, actual.getState(), "The node was not in state skipped.");
		return this;
	}

	public NodeResultAssert isFailed() {
		assertEquals(ResultState.FAILED, actual.getState(), "The node was not in state failed.");
		return this;
	}

	/**
	 * Assert that the result contains an output for the given key.
	 */
	public NodeResultAssert hasOutput(NodeOutputKey<?> key) {
		assertTrue(actual.has(key), "Expected output key '" + key.key() + "' but it was not present. Found: " + actual.getOutputs().keySet());
		return this;
	}

	/**
	 * Assert that the result contains a specific value for the given typed key.
	 */
	public <T> NodeResultAssert hasOutput(NodeOutputKey<T> key, T expectedValue) {
		assertTrue(actual.has(key), "Expected output key '" + key.key() + "' but it was not present. Found: " + actual.getOutputs().keySet());
		T actualValue = actual.get(key);
		assertNotNull(actualValue, "Output value for '" + key.key() + "' was null.");
		assertEquals(expectedValue, actualValue, "Output value for '" + key.key() + "' did not match.");
		return this;
	}

	/**
	 * Assert that the result has exactly the given number of outputs.
	 */
	public NodeResultAssert hasOutputCount(int count) {
		int actualCount = actual.getOutputs().size();
		assertEquals(count, actualCount, "Expected " + count + " outputs but found " + actualCount + ": " + actual.getOutputs().keySet());
		return this;
	}
}
