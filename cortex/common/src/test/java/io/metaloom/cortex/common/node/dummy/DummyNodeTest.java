package io.metaloom.cortex.common.node.dummy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

	/**
	 * The reporting methods read the media hash to build their line prefix. That read goes to an extended attribute and throws for media that has
	 * gone away, is unreadable, or sits on a filesystem without xattr support. Reporting runs inside the processor's per-node catch block, so a throw
	 * here aborts the whole scan and discards the failure that was being reported.
	 */
	@Test
	public void testReportingSurvivesUnreadableHash() {
		DummyNode node = new DummyNode(null, new CortexOptions(), new DummyOptions());

		LoomMedia media = mock(LoomMedia.class);
		when(media.getSHA512()).thenThrow(new RuntimeException("Could not read property loom_sha512"));

		assertDoesNotThrow(() -> node.error(media, "Error while processing node dummy"));
		assertDoesNotThrow(() -> node.print(ctx(media), "FAILED", "NA"));
	}

	@Test
	public void testReportingSurvivesNullMedia() {
		DummyNode node = new DummyNode(null, new CortexOptions(), new DummyOptions());

		assertDoesNotThrow(() -> node.error(null, "Error while processing node dummy"));
	}
}
