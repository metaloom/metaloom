package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.SegmentNode;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.pipeline.model.SegmentTaskResult;
import io.vertx.core.json.JsonObject;

/**
 * Wire round-trips for the segment messages.
 *
 * <p>These payloads only ever exist as JSON between two processes, so a field that
 * fails to survive serialisation is invisible until a real worker silently receives
 * a node with no options, or an empty dependency list that changes what runs.</p>
 */
public class SegmentProtocolSerdeTest {

	private static SegmentTask sampleTask() {
		return new SegmentTask(UUID.randomUUID(), UUID.randomUUID(), "item-1", "video[decode,face]", "video",
			MediaRef.of("/media/a.mp4"),
			List.of(
				new SegmentNode("decode", "video-decode", true, Map.of("fps", 25), List.of("src")),
				new SegmentNode("face", "facedetect", false, Map.of("model", "retina"), List.of("decode"))),
			Map.of("src", Map.of("path", "/media/a.mp4")));
	}

	@Test
	void testSegmentTaskSurvivesTheRoundTrip() {
		SegmentTask original = sampleTask();

		SegmentTask parsed = JsonObject.mapFrom(original).mapTo(SegmentTask.class);

		assertEquals(original.getTaskUuid(), parsed.getTaskUuid());
		assertEquals(original.getRunUuid(), parsed.getRunUuid());
		assertEquals("item-1", parsed.getItemId());
		assertEquals("video[decode,face]", parsed.getSegmentId());
		assertEquals("video", parsed.getAffinity());
		assertEquals("/media/a.mp4", parsed.getMedia().getPath());
	}

	@Test
	void testNodeOrderIsPreserved() {
		SegmentTask parsed = JsonObject.mapFrom(sampleTask()).mapTo(SegmentTask.class);

		// The worker executes the list as given, so order is load-bearing rather than
		// cosmetic - a reordered segment would run nodes before their dependencies.
		assertEquals(List.of("decode", "face"),
			parsed.getNodes().stream().map(SegmentNode::getNodeId).toList());
	}

	@Test
	void testPerNodeOptionsAndBlockingSurvive() {
		SegmentTask parsed = JsonObject.mapFrom(sampleTask()).mapTo(SegmentTask.class);

		SegmentNode decode = parsed.getNodes().get(0);
		SegmentNode face = parsed.getNodes().get(1);

		assertEquals(25, decode.getOptions().get("fps"));
		assertEquals("retina", face.getOptions().get("model"));
		// Blocking decides whether a node is skipped when its dependency fails. Losing
		// it would silently change behaviour rather than error.
		assertTrue(decode.isBlocking());
		assertTrue(!face.isBlocking());
	}

	@Test
	void testDependenciesSurvive() {
		SegmentTask parsed = JsonObject.mapFrom(sampleTask()).mapTo(SegmentTask.class);

		assertEquals(List.of("src"), parsed.getNodes().get(0).getDependencies());
		assertEquals(List.of("decode"), parsed.getNodes().get(1).getDependencies());
	}

	@Test
	void testUpstreamOutputsSurvive() {
		SegmentTask parsed = JsonObject.mapFrom(sampleTask()).mapTo(SegmentTask.class);

		assertEquals("/media/a.mp4", parsed.getUpstreamOutputs().get("src").get("path"));
	}

	@Test
	void testNodeKindsAreDerivedNotSerialised() {
		SegmentTask original = sampleTask();
		JsonObject json = JsonObject.mapFrom(original);

		// Derived from the node list, so serialising it would let the two disagree.
		assertNull(json.getJsonArray("nodeKinds"));
		assertEquals(List.of("video-decode", "facedetect"), original.getNodeKinds());
	}

	@Test
	void testSegmentResultSurvivesTheRoundTrip() {
		UUID taskUuid = UUID.randomUUID();
		SegmentTaskResult original = new SegmentTaskResult(taskUuid, "item-1", "seg-1",
			List.of(
				NodeTaskResult.completed(taskUuid, "decode", 120, Map.of("frames", 300)),
				NodeTaskResult.skipped("face", "Dependency decode failed")),
			null);

		SegmentTaskResult parsed = JsonObject.mapFrom(original).mapTo(SegmentTaskResult.class);

		assertEquals(2, parsed.getResults().size());
		// Per-node outcomes, never one verdict for the segment: a single status would
		// turn one bad node into a wholly failed item.
		assertEquals(NodeState.COMPLETED, parsed.getResults().get(0).getState());
		assertEquals(300, parsed.getResults().get(0).getOutputs().get("frames"));
		assertEquals(NodeState.SKIPPED, parsed.getResults().get(1).getState());
		assertEquals("Dependency decode failed", parsed.getResults().get(1).getMessage());
	}

	@Test
	void testASegmentLevelErrorSurvives() {
		SegmentTaskResult original = new SegmentTaskResult(UUID.randomUUID(), "item-1", "seg-1", List.of(),
			"file vanished");

		SegmentTaskResult parsed = JsonObject.mapFrom(original).mapTo(SegmentTaskResult.class);

		assertEquals("file vanished", parsed.getError());
		assertTrue(parsed.getResults().isEmpty());
	}

	@Test
	void testASingleNodeSegmentIsValidOnTheWire() {
		SegmentTask original = new SegmentTask(UUID.randomUUID(), UUID.randomUUID(), "item-1", "seg-1", "default",
			MediaRef.of("/media/a.mp4"),
			List.of(new SegmentNode("hash", "sha512", true, Map.of(), List.of())), Map.of());

		SegmentTask parsed = JsonObject.mapFrom(original).mapTo(SegmentTask.class);

		assertEquals(1, parsed.getNodes().size());
		assertEquals("sha512", parsed.getNodes().get(0).getNodeKind());
	}

}
