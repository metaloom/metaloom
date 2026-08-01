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

import io.metaloom.cortex.common.media.MediaReferenceResolver;
import io.metaloom.cortex.common.metrics.NoopCortexMetrics;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.loader.NodeFactory;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
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
		// The media loader is never reached: every node here either refuses to be built or
		// blocks, both of which happen before media resolution.
		return new PipelineTaskHandler(factory, null, new MediaReferenceResolver(null), NoopCortexMetrics.INSTANCE);
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

	private static PipelineNode await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return null;
	}
}
