package io.metaloom.cortex.node.tag;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.inject.Singleton;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.common.node.PipelineConfigurable;

/**
 * A {@link PipelineConfigurable} node must not be a Dagger singleton.
 *
 * <p>
 * {@code configure(JsonObject)} mutates the instance, and the runner builds one per task. A
 * {@code @Singleton} here would mean two tag nodes in one graph — or two graphs on one worker —
 * sharing rules, which is worse than a crash: the second instance would quietly tag by the first
 * one's configuration, in a namespace every user of the instance shares.
 * </p>
 */
class TagNodeSingletonTest {

	@Test
	void testTheNodeIsPipelineConfigurable() {
		assertTrue(PipelineConfigurable.class.isAssignableFrom(TagNode.class),
			"The rules live in the pipeline definition, so the node has to be configured per instance");
	}

	@Test
	void testTheNodeIsNotASingleton() {
		assertNull(TagNode.class.getAnnotation(Singleton.class),
			"TagNode is a PipelineConfigurable and is mutated by configure(); it must not be scoped");
	}
}
