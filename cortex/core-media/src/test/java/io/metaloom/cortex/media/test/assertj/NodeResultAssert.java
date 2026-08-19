package io.metaloom.cortex.media.test.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
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
		assertEquals(ResultState.FAILED, actual.getState(), "The node was not in state failed. Message: " + actual.getMessage());
		return this;
	}

	/**
	 * Assert the skip reason / failure cause the result carries.
	 *
	 * <p>
	 * Worth asserting separately from the state: the historical bug was not only that
	 * {@code ctx.failure(cause).next()} reported SUCCESS, but that it dropped the cause on the way, so
	 * even a result whose state was later corrected could arrive with nothing to diagnose from.
	 * </p>
	 */
	public NodeResultAssert hasMessage(String expected) {
		assertEquals(expected, actual.getMessage(), "The result message did not match.");
		return this;
	}

	/**
	 * Assert that the result's message contains the given text — for a cause built from an exception
	 * message the test does not want to spell in full.
	 */
	public NodeResultAssert hasMessageContaining(String expected) {
		assertNotNull(actual.getMessage(), "The result carried no message at all; expected one containing: " + expected);
		assertTrue(actual.getMessage().contains(expected),
			"The result message did not contain '" + expected + "'. It was: " + actual.getMessage());
		return this;
	}

	/**
	 * Assert that the node emitted something on the given output port.
	 */
	public NodeResultAssert hasOutput(OutputPort<?> port) {
		assertTrue(actual.has(port),
			"Expected an emission on port '" + port.id() + "' but there was none. Ports emitted: " + actual.getOutputs().keySet());
		return this;
	}

	/**
	 * Assert that a {@code ONE} output port carries a specific value.
	 */
	public <T> NodeResultAssert hasOutput(OutputPort<T> port, T expectedValue) {
		assertTrue(actual.has(port),
			"Expected an emission on port '" + port.id() + "' but there was none. Ports emitted: " + actual.getOutputs().keySet());
		T actualValue = actual.get(port);
		assertNotNull(actualValue, "The value on port '" + port.id() + "' was null.");
		assertEquals(expectedValue, actualValue, "The value on port '" + port.id() + "' did not match.");
		return this;
	}

	/**
	 * Assert that the node emitted nothing on the given output port.
	 */
	public NodeResultAssert hasNoOutput(OutputPort<?> port) {
		assertTrue(!actual.has(port),
			"Expected nothing on port '" + port.id() + "' but it carried: " + actual.getOutputs().get(port.id()));
		return this;
	}

	/**
	 * Assert the elements of a {@code MANY} output port, in emission order.
	 *
	 * <p>
	 * Order is part of the contract rather than incidental: a consumer zips two fanned-out branches
	 * by element index, so a producer that emits the same values in a different order is a different
	 * result.
	 * </p>
	 */
	public <T> NodeResultAssert hasElements(OutputPort<T> port, List<T> expected) {
		assertEquals(expected, actual.elements(port), "The elements on port '" + port.id() + "' did not match.");
		return this;
	}

	/**
	 * Assert how many elements a {@code MANY} output port carries.
	 */
	public NodeResultAssert hasElementCount(OutputPort<?> port, int count) {
		int actualCount = actual.has(port) ? actual.getOutputs().get(port.id()).size() : 0;
		assertEquals(count, actualCount,
			"Expected " + count + " element(s) on port '" + port.id() + "' but found " + actualCount + ".");
		return this;
	}

	/**
	 * Assert that the result has exactly the given number of output ports.
	 */
	public NodeResultAssert hasOutputCount(int count) {
		int actualCount = actual.getOutputs().size();
		assertEquals(count, actualCount, "Expected " + count + " output ports but found " + actualCount + ": " + actual.getOutputs().keySet());
		return this;
	}
}
