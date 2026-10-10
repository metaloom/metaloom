package io.metaloom.cortex.impl.loom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.common.media.MediaReferenceResolver;
import io.metaloom.cortex.common.metrics.CortexMetrics;
import io.metaloom.cortex.common.metrics.NoopCortexMetrics;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.loader.NodeFactory;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.SegmentNode;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.vertx.core.json.JsonObject;

/**
 * What a shutting-down worker tells Loom about the work it is holding.
 *
 * <p>A worker that simply exits leaves every task it held {@code RUNNING} until the
 * lease lapses, and the lease is deliberately generous - so a scale-down costs the
 * affected runs a lease interval per task before any of it is placed again. These
 * tests pin the two halves of closing that gap: work arriving after the announcement
 * is refused rather than started, and work still running at the deadline is named
 * explicitly so Loom can place it elsewhere.</p>
 */
public class PipelineTaskHandlerDrainTest {

	private final List<ProcessorMessage> sent = new CopyOnWriteArrayList<>();

	private PipelineTaskHandler handler(Function<JsonObject, PipelineNode> create) {
		return handler(create, NoopCortexMetrics.INSTANCE);
	}

	private PipelineTaskHandler handler(Function<JsonObject, PipelineNode> create, CortexMetrics metrics) {
		NodeFactory factory = new NodeFactory() {

			@Override
			public PipelineNode createNode(JsonObject nodeDef) {
				return create.apply(nodeDef);
			}

			@Override
			public Set<String> registeredTypes() {
				return Set.of("sha512");
			}
		};
		// There is no media loader: every node here either refuses to be built or blocks, both of
		// which happen before a node task resolves its media. A segment resolves media before it
		// builds anything, so the resolver answers with no media rather than reaching the loader.
		MediaReferenceResolver resolver = new MediaReferenceResolver(null) {

			@Override
			public LoomMedia resolve(MediaRef ref) {
				return null;
			}
		};
		return new PipelineTaskHandler(factory, null, resolver, metrics);
	}

	private NodeTask task(String itemId, String nodeId) {
		return new NodeTask(UUID.randomUUID(), UUID.randomUUID(), itemId, nodeId, "sha512",
			MediaRef.of("/media/a.mp4"), Map.of(), Map.of());
	}

	/** Poll rather than sleep a fixed time, so the test is not a race on a slow machine. */
	private void awaitInFlight(PipelineTaskHandler handler, int expected) throws InterruptedException {
		for (int i = 0; i < 200 && handler.inFlightCount() != expected; i++) {
			Thread.sleep(10);
		}
		assertEquals(expected, handler.inFlightCount(), "Timed out waiting for the in-flight count");
	}

	private ProcessorMessage onlyMessage() {
		assertEquals(1, sent.size(), "Expected exactly one message, got " + sent);
		return sent.get(0);
	}

	@Test
	void testWorkArrivingAfterTheAnnouncementIsHandedStraightBack() {
		// Loom stops placing work on a TERMINATING worker, but a dispatch already on the
		// wire still lands here. Starting it would mean abandoning it seconds later.
		PipelineTaskHandler handler = handler(def -> fail("A draining worker must not build a node"));
		handler.beginDrain();

		handler.handleNodeTask(task("item-1", "hash"), sent::add);

		ProcessorMessage message = onlyMessage();
		assertEquals(ProcessorMessageType.TASK_RETURNED, message.getType());
		assertEquals("item-1", message.getBody().getString("itemId"));
		assertEquals(List.of("hash"), message.getBody().getJsonArray("nodeIds").getList());
		assertEquals(0, handler.inFlightCount(), "A refused task was never taken on");
	}

	@Test
	void testWorkStillRunningAtTheDeadlineIsReturned() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		PipelineTaskHandler handler = handler(def -> {
			await(release);
			throw new IllegalStateException("released");
		});

		handler.handleNodeTask(task("item-1", "hash"), sent::add);
		awaitInFlight(handler, 1);

		handler.beginDrain();
		assertEquals(1, handler.awaitDrain(150), "The task is still running, so the wait must time out");

		assertEquals(1, handler.returnOutstanding("worker is shutting down"),
			"Unfinished work must be named rather than silently abandoned to the lease");
		ProcessorMessage message = onlyMessage();
		assertEquals(ProcessorMessageType.TASK_RETURNED, message.getType());
		assertEquals("item-1", message.getBody().getString("itemId"));
		assertTrue(message.getBody().getString("reason").contains("shutting down"));

		release.countDown();
	}

	@Test
	void testWorkThatFinishesInTimeIsNotReturned() throws Exception {
		// The node refuses to build, which the runner converts into a failed result - a
		// settled task either way, and the drain must treat it as done rather than
		// re-place work that has already been answered for.
		PipelineTaskHandler handler = handler(def -> {
			throw new IllegalStateException("unknown node kind");
		});

		handler.handleNodeTask(task("item-1", "hash"), sent::add);
		awaitInFlight(handler, 0);

		handler.beginDrain();
		assertEquals(0, handler.awaitDrain(2000), "A finished task must not hold the drain open");
		assertEquals(0, handler.returnOutstanding("worker is shutting down"));

		assertEquals(ProcessorMessageType.NODE_TASK_RESULT_BATCH, onlyMessage().getType(),
			"The worker answered the task; there is nothing to hand back");
	}

	@Test
	void testAnIdleWorkerDrainsWithoutWaiting() {
		PipelineTaskHandler handler = handler(def -> fail("Nothing was dispatched"));

		assertEquals(0, handler.beginDrain());
		long start = System.currentTimeMillis();
		assertEquals(0, handler.awaitDrain(5000));
		assertTrue(System.currentTimeMillis() - start < 1000,
			"An idle worker must not sit out the whole grace period before exiting");
		assertTrue(handler.isDraining());
	}

	@Test
	void testAnUnexpectedErrorIsAnsweredForTheRightElement() throws Exception {
		// An Error escapes NodeTaskRunner's own Exception handling and is answered by the handler.
		// That answer used to be stamped element 0, so a per-element task settled a sibling element
		// and left its own waiting for the lease.
		PipelineTaskHandler handler = handler(def -> {
			throw new LinkageError("native library missing");
		});
		NodeTask task = new NodeTask(UUID.randomUUID(), UUID.randomUUID(), "item-1", "hash", "sha512", 3,
			MediaRef.of("/media/a.mp4"), Map.of(), Map.of(), null, 1);

		handler.handleNodeTask(task, sent::add);
		for (int i = 0; i < 200 && sent.isEmpty(); i++) {
			Thread.sleep(10);
		}

		ProcessorMessage message = onlyMessage();
		assertEquals(ProcessorMessageType.NODE_TASK_RESULT_BATCH, message.getType());
		JsonObject entry = message.getBody().getJsonArray("entries").getJsonObject(0).getJsonObject("result");
		assertEquals("FAILED", entry.getString("state"));
		assertEquals(3, entry.getInteger("elementSeq").intValue());
	}

	@Test
	void testFlushPendingResultsSendsAPartialBatch() throws Exception {
		// A finished task leaves the in-flight set before its result is batched, so a drain does
		// not hand it back. Its result must then go out before the connection closes rather than
		// wait in a partial batch that dies with the socket.
		PipelineTaskHandler handler = handler(def -> {
			throw new IllegalStateException("unknown node kind");
		});
		NodeTask task = new NodeTask(UUID.randomUUID(), UUID.randomUUID(), "item-1", "hash", "sha512", 0,
			MediaRef.of("/media/a.mp4"), Map.of(), Map.of(), null, 50);

		handler.handleNodeTask(task, sent::add);
		// The result is batched just after the task leaves the in-flight set; poll briefly - far
		// inside the batcher's own hold time - until it is there to flush.
		int flushed = 0;
		for (int i = 0; i < 40 && flushed == 0 && sent.isEmpty(); i++) {
			Thread.sleep(5);
			flushed = handler.flushPendingResults();
		}

		assertEquals(1, flushed);
		assertEquals(ProcessorMessageType.NODE_TASK_RESULT_BATCH, onlyMessage().getType());
	}

	@Test
	void testASegmentResultIsCountedUnderItsNodeKind() throws Exception {
		// A segment answers one result per node, and each is a node operation. The id on that
		// result is the graph-local node id, which is per pipeline: counting under it split one
		// kind across as many series as there were pipelines, none of them the series a single
		// node task of the same kind lands in.
		List<String> operations = new CopyOnWriteArrayList<>();
		CortexMetrics metrics = new NoopCortexMetrics() {

			@Override
			public void recordNodeOperation(String nodeKind, ResultState state, long durationMs) {
				operations.add(nodeKind + ":" + state);
			}
		};
		PipelineTaskHandler handler = handler(def -> {
			throw new IllegalStateException("unknown node kind");
		}, metrics);
		SegmentTask task = new SegmentTask(UUID.randomUUID(), UUID.randomUUID(), "item-1", "seg-1", "gpu",
			MediaRef.of("/media/a.mp4"), List.of(new SegmentNode("n7", "sha512", true, Map.of(), List.of())), Map.of());

		handler.handleSegmentTask(task, sent::add);
		for (int i = 0; i < 200 && sent.isEmpty(); i++) {
			Thread.sleep(10);
		}

		assertEquals(ProcessorMessageType.SEGMENT_TASK_RESULT, onlyMessage().getType());
		assertEquals(List.of("sha512:FAILED"), operations,
			"A segment member is counted under its node kind, as a single node task is - not under the graph-local id");
	}

	private static PipelineNode await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return null;
	}
}
