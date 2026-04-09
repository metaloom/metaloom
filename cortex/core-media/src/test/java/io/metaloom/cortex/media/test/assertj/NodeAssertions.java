package io.metaloom.cortex.media.test.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;

public class NodeAssertions extends Assertions {

	public static NodeResultAssert assertThat(NodeResult actual) {
		return new NodeResultAssert(actual);
	}

	public static LoomMediaAssert assertThat(LoomMedia actual) {
		return new LoomMediaAssert(actual);
	}

}
