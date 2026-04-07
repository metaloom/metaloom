package io.metaloom.cortex.common.node.dummy;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;

public class DummyNodeTest extends AbstractMediaTest {

	@Test
	public void testDummyNode() throws IOException {
		DummyNode node = new DummyNode(null, new CortexOptions(), new DummyOptions());

		LoomMedia media = mock(LoomMedia.class);
		node.process(ctx(media));
		assertTrue(node.wasInvoked());
	}
}
