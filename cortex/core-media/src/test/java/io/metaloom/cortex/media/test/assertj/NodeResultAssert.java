package io.metaloom.cortex.media.test.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;

public class NodeResultAssert extends AbstractAssert<NodeResultAssert, NodeResult<?>> {

	protected NodeResultAssert(NodeResult<?> actual) {
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
}
