package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import java.time.Instant;

import io.metaloom.loom.rest.model.processor.ProcessorResponse;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.model.processor.event.ProcessorEventMessage;
import io.metaloom.loom.rest.model.processor.event.ProcessorEventType;
import io.metaloom.loom.rest.model.processor.message.NodeTaskResultMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.SourceCompleteMessage;
import io.metaloom.loom.rest.model.processor.message.SourceItemsMessage;
import io.metaloom.loom.rest.model.processor.message.SourceTaskMessage;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

/**
 * Round-trip tests for the Variant C processor protocol.
 *
 * <p>These encode exactly as Loom does and decode exactly as Cortex will, so the
 * wire contract is pinned before the Cortex side is written against it. A protocol
 * whose two ends are implemented separately and never tested together is how the
 * {@code edges[]} / {@code dependencies[]} divergence happened in the first place.</p>
 */
public class ProcessorProtocolSerdeTest {

	/** Encode the way {@code ProcessorRegistry.send} does. */
	private static String encode(ProcessorMessageType type, Object body) {
		return Json.encode(new ProcessorMessage(type, body == null ? null : JsonObject.mapFrom(body)));
	}

	/** Decode the way the receiving side does. */
	private static ProcessorMessage decode(String wire) {
		return new JsonObject(wire).mapTo(ProcessorMessage.class);
	}

	@Test
	void testEnvelopeShapeIsTypeAndBody() {
		String wire = encode(ProcessorMessageType.SOURCE_ITEMS_ACK,
			new io.metaloom.loom.rest.model.processor.message.SourceItemsAckMessage()
				.setRunUuid(UUID.randomUUID()).setSeq(7));

		JsonObject raw = new JsonObject(wire);
		assertEquals("SOURCE_ITEMS_ACK", raw.getString("type"));
		assertNotNull(raw.getJsonObject("body"));
		assertEquals(7, raw.getJsonObject("body").getLong("seq"));
	}

	@Test
	void testNullBodyProducesAValidEnvelope() {
		// The envelope used to be assembled by string concatenation; a null body is
		// exactly the sort of case that produced malformed JSON.
		ProcessorMessage decoded = decode(encode(ProcessorMessageType.HEARTBEAT_ACK, null));

		assertEquals(ProcessorMessageType.HEARTBEAT_ACK, decoded.getType());
		assertNull(decoded.getBody());
	}

	@Test
	void testNodeTaskSurvivesTheRoundTrip() {
		UUID taskUuid = UUID.randomUUID();
		UUID runUuid = UUID.randomUUID();
		NodeTask task = new NodeTask(taskUuid, runUuid, "item-1", "hash", "sha512",
			new MediaRef("/media/holiday.mp4", "abc123", 4096),
			Map.of("algorithm", "sha512", "chunkSize", 1024),
			Map.of("src", Map.of("path", "/media/holiday.mp4", "source", "filesystem-source")));

		NodeTask decoded = decode(encode(ProcessorMessageType.NODE_TASK, task))
			.getBody().mapTo(NodeTask.class);

		assertEquals(taskUuid, decoded.getTaskUuid());
		assertEquals(runUuid, decoded.getRunUuid());
		assertEquals("item-1", decoded.getItemId());
		assertEquals("hash", decoded.getNodeId());
		assertEquals("sha512", decoded.getNodeKind());
		assertEquals("/media/holiday.mp4", decoded.getMedia().getPath());
		assertEquals("abc123", decoded.getMedia().getSha512());
		assertEquals(4096, decoded.getMedia().getSize());
		assertEquals("sha512", decoded.getOptions().get("algorithm"));
		assertEquals(1024, decoded.getOptions().get("chunkSize"));
		assertEquals("/media/holiday.mp4", decoded.getUpstreamOutputs().get("src").get("path"));
	}

	@Test
	void testAwkwardPathCharactersAreEscaped() {
		// Quotes, backslashes, newlines and non-ASCII in a filename must not be able to
		// break the envelope open.
		String nasty = "/media/it's \"a\\test\"\n\tresumé ü.mp4";
		NodeTask task = new NodeTask(UUID.randomUUID(), null, "item-1", "n", "sha512",
			MediaRef.of(nasty), Map.of("note", "line1\nline2 \"quoted\""), Map.of());

		NodeTask decoded = decode(encode(ProcessorMessageType.NODE_TASK, task))
			.getBody().mapTo(NodeTask.class);

		assertEquals(nasty, decoded.getMedia().getPath(),
			"A path containing quotes and newlines must survive the envelope intact");
		assertEquals("line1\nline2 \"quoted\"", decoded.getOptions().get("note"));
	}

	@Test
	void testNodeTaskResultMessageRoundTrip() {
		UUID taskUuid = UUID.randomUUID();
		NodeTaskResultMessage message = new NodeTaskResultMessage()
			.setRunUuid(UUID.randomUUID())
			.setItemId("item-42")
			.setResult(NodeTaskResult.completed(taskUuid, "hash", 123, Map.of("sha512", "deadbeef")));

		NodeTaskResultMessage decoded = decode(encode(ProcessorMessageType.NODE_TASK_RESULT, message))
			.getBody().mapTo(NodeTaskResultMessage.class);

		assertEquals("item-42", decoded.getItemId());
		assertEquals(NodeState.COMPLETED, decoded.getResult().getState());
		assertEquals(taskUuid, decoded.getResult().getTaskUuid());
		assertEquals(123, decoded.getResult().getDurationMs());
		assertEquals("deadbeef", decoded.getResult().getOutputs().get("sha512"));
	}

	@Test
	void testFailedResultCarriesItsMessage() {
		NodeTaskResultMessage message = new NodeTaskResultMessage()
			.setItemId("item-1")
			.setResult(NodeTaskResult.failed(UUID.randomUUID(), "whisper", 900, "model not found"));

		NodeTaskResultMessage decoded = decode(encode(ProcessorMessageType.NODE_TASK_RESULT, message))
			.getBody().mapTo(NodeTaskResultMessage.class);

		assertEquals(NodeState.FAILED, decoded.getResult().getState());
		assertEquals("model not found", decoded.getResult().getMessage());
	}

	@Test
	void testFilterVerdictSurvivesAsABoolean() {
		// Filter routing depends on reading this back as a boolean rather than a string.
		NodeTaskResultMessage message = new NodeTaskResultMessage()
			.setItemId("item-1")
			.setResult(NodeTaskResult.completed(UUID.randomUUID(), "flt", 1,
				Map.of("filter_passed", true, "filter_reason", "image/*")));

		NodeTaskResultMessage decoded = decode(encode(ProcessorMessageType.NODE_TASK_RESULT, message))
			.getBody().mapTo(NodeTaskResultMessage.class);

		assertEquals(Boolean.TRUE, decoded.getResult().getFilterPassed());
	}

	@Test
	void testSourceTaskRoundTrip() {
		UUID runUuid = UUID.randomUUID();
		SourceTaskMessage message = new SourceTaskMessage()
			.setRunUuid(runUuid)
			.setNodeId("src")
			.setNodeKind("filesystem-source")
			.setOptions(Map.of("pathGlobs", List.of("/media/**/*.mp4")))
			.setBatchSize(250);

		SourceTaskMessage decoded = decode(encode(ProcessorMessageType.SOURCE_TASK, message))
			.getBody().mapTo(SourceTaskMessage.class);

		assertEquals(runUuid, decoded.getRunUuid());
		assertEquals("filesystem-source", decoded.getNodeKind());
		assertEquals(250, decoded.getBatchSize());
		assertEquals(List.of("/media/**/*.mp4"), decoded.getOptions().get("pathGlobs"));
	}

	@Test
	void testSourceItemsBatchRoundTrip() {
		SourceItemsMessage message = new SourceItemsMessage()
			.setRunUuid(UUID.randomUUID())
			.setSeq(3)
			.setItems(List.of(
				new MediaRef("/media/a.mp4", null, 100),
				new MediaRef("/media/b.mp4", null, 200)));

		SourceItemsMessage decoded = decode(encode(ProcessorMessageType.SOURCE_ITEMS, message))
			.getBody().mapTo(SourceItemsMessage.class);

		assertEquals(3, decoded.getSeq());
		assertEquals(2, decoded.getItems().size());
		assertEquals("/media/a.mp4", decoded.getItems().get(0).getPath());
		assertEquals(200, decoded.getItems().get(1).getSize());
	}

	@Test
	void testSourceCompleteCarriesErrorWhenEnumerationAborted() {
		SourceCompleteMessage message = new SourceCompleteMessage()
			.setRunUuid(UUID.randomUUID())
			.setTotalCount(17)
			.setError("permission denied on /media/private");

		SourceCompleteMessage decoded = decode(encode(ProcessorMessageType.SOURCE_COMPLETE, message))
			.getBody().mapTo(SourceCompleteMessage.class);

		assertEquals(17, decoded.getTotalCount());
		assertTrue(decoded.getError().contains("permission denied"));
	}

	@Test
	void testProcessorEventCarriesChannelDiscriminatorAndSnapshot() {
		// Processor events share the UI socket with pipeline events; the `channel`
		// discriminator is how a client routes a frame without a second connection.
		ProcessorResponse snapshot = new ProcessorResponse()
			.setNodeId("node-1")
			.setName("cortex-gpu-01")
			.setState(ProcessorState.PAUSED);
		ProcessorEventMessage event = new ProcessorEventMessage(ProcessorEventType.STATE_CHANGED, "node-1")
			.setProcessor(snapshot);

		String wire = Json.encode(event);
		JsonObject raw = new JsonObject(wire);
		assertEquals("PROCESSOR", raw.getString("channel"), "channel discriminator must always serialize");
		assertEquals("STATE_CHANGED", raw.getString("type"));

		ProcessorEventMessage decoded = new JsonObject(wire).mapTo(ProcessorEventMessage.class);
		assertEquals(ProcessorEventType.STATE_CHANGED, decoded.getType());
		assertEquals("node-1", decoded.getNodeId());
		assertNotNull(decoded.getProcessor());
		assertEquals("node-1", decoded.getProcessor().getNodeId());
		assertEquals(ProcessorState.PAUSED, decoded.getProcessor().getState());
	}

	@Test
	void testHeartbeatEventIsLightweight() {
		Instant seen = Instant.now();
		ProcessorEventMessage event = new ProcessorEventMessage(ProcessorEventType.HEARTBEAT, "node-2")
			.setLastSeen(seen);

		ProcessorEventMessage decoded = new JsonObject(Json.encode(event)).mapTo(ProcessorEventMessage.class);
		assertEquals(ProcessorEventType.HEARTBEAT, decoded.getType());
		assertEquals("node-2", decoded.getNodeId());
		assertNull(decoded.getProcessor(), "heartbeat must not carry a full snapshot");
		assertEquals(seen, decoded.getLastSeen());
	}

	@Test
	void testEveryNewMessageTypeIsNameStable() {
		// The Cortex side maps these by name. Renaming one silently breaks the bridge
		// at runtime rather than at compile time.
		for (String name : List.of("SOURCE_TASK", "SOURCE_ITEMS", "SOURCE_ITEMS_ACK",
			"SOURCE_COMPLETE", "NODE_TASK", "NODE_TASK_RESULT")) {
			assertNotNull(ProcessorMessageType.valueOf(name), name + " must exist");
		}
	}
}
