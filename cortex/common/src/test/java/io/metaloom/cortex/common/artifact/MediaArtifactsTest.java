package io.metaloom.cortex.common.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.artifact.ArtifactCache;
import io.metaloom.cortex.api.node.artifact.impl.ScopedArtifactCache;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;

/**
 * The decode two real image nodes share.
 *
 * <p>
 * {@code QualityNode} and {@code DominantColorNode} both start from {@code ImageIO.read} of the same file. Grouping them in one segment used to mean
 * decoding that file twice; each call here stands for one of them.
 * </p>
 */
public class MediaArtifactsTest extends AbstractMediaTest {

	/** What each of the two nodes sees: its own context, the segment's one scope. */
	private NodeContext<LoomMedia> nodeIn(ArtifactCache scope, LoomMedia media) {
		return NodeContext.create(media, NodeInputs.empty().withArtifacts(scope));
	}

	@Test
	public void testTwoImageNodesInOneSegmentDecodeTheFileOnce() throws IOException {
		LoomMedia media = mediaImage1();
		try (ScopedArtifactCache segment = new ScopedArtifactCache("item-1")) {
			BufferedImage quality = MediaArtifacts.decodedImageOrNull(nodeIn(segment, media));
			BufferedImage color = MediaArtifacts.decodedImage(nodeIn(segment, media));

			// Same object, so there was one ImageIO.read - the second node read the first
			// node's decode rather than opening the file again.
			assertSame(quality, color, "Both image nodes must share one decode");
			assertTrue(segment.retainedBytes() > 0, "The scope must account for what it is holding");
		}
	}

	@Test
	public void testTheDecodeIsNotSharedAcrossItems() throws IOException {
		LoomMedia media = mediaImage1();
		BufferedImage first;
		BufferedImage second;
		try (ScopedArtifactCache itemA = new ScopedArtifactCache("item-A")) {
			first = MediaArtifacts.decodedImage(nodeIn(itemA, media));
		}
		try (ScopedArtifactCache itemB = new ScopedArtifactCache("item-B")) {
			second = MediaArtifacts.decodedImage(nodeIn(itemB, media));
		}

		// Even for the same path: a scope covers one execution, and the file may have changed
		// between them. Nothing decides that on the node's behalf.
		assertNotSame(first, second);
	}

	@Test
	public void testWithoutASegmentEachNodeDecodesAsItAlwaysDid() throws IOException {
		LoomMedia media = mediaImage1();
		// NodeContext.create(media) has no scope, so the artifact API falls back to the no-op
		// one. Standalone behaviour is unchanged by any of this.
		BufferedImage first = MediaArtifacts.decodedImage(NodeContext.create(media));
		BufferedImage second = MediaArtifacts.decodedImage(NodeContext.create(media));

		assertNotSame(first, second);
	}

	@Test
	public void testAnUndecodableFileIsReportedTheTwoWaysItsCallersExpect() throws IOException {
		LoomMedia media = mediaBogusBin();
		try (ScopedArtifactCache segment = new ScopedArtifactCache("item-1")) {
			// quality reports a verdict on the file...
			assertNull(MediaArtifacts.decodedImageOrNull(nodeIn(segment, media)));
			// ...dominant-color treats it as a failure. Both had these behaviours before the
			// artifact scope existed and must still have them.
			IOException e = assertThrows(IOException.class, () -> MediaArtifacts.decodedImage(nodeIn(segment, media)));
			assertTrue(e.getMessage().startsWith("No image reader could decode"), e.getMessage());
		}
	}

	@Test
	public void testAFailedDecodeIsNotCachedAsAVerdict() throws IOException {
		LoomMedia media = mediaBogusBin();
		try (ScopedArtifactCache segment = new ScopedArtifactCache("item-1")) {
			assertNull(MediaArtifacts.decodedImageOrNull(nodeIn(segment, media)));

			// A factory that throws publishes nothing, so the scope holds no "this file is
			// undecodable" entry - each node reaches its own conclusion.
			assertEquals(0, segment.retainedBytes());
			assertTrue(segment.peek(MediaArtifacts.DECODED_IMAGE).isEmpty());
		}
	}
}
