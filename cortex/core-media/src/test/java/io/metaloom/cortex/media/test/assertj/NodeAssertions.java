package io.metaloom.cortex.media.test.assertj;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.assertj.OptionsAssertions;

/**
 * Entry point for cortex node and media AssertJ assertions.
 *
 * <p>Extends {@link OptionsAssertions} (which in turn extends AssertJ's own
 * {@code Assertions}), so a single static import gives a test the media
 * assertions, the node options assertions, and the whole of core AssertJ:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
 * </pre>
 *
 * <p>The chain is built from classes rather than interfaces because static
 * methods declared on an interface are <em>not</em> inherited and cannot be
 * re-exposed through a sub-interface. Node modules continue the chain with
 * their own {@code <Node>NodeAssertions}.</p>
 */
public class NodeAssertions extends OptionsAssertions {

	public static NodeResultAssert assertThat(NodeResult actual) {
		return new NodeResultAssert(actual);
	}

	public static LoomMediaAssert assertThat(LoomMedia actual) {
		return new LoomMediaAssert(actual);
	}

}
