package io.metaloom.loom.rest.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.processor.workorder.WorkOrderResult;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderStatus;

/**
 * Tests the work-order callback registry, in particular the timeout path that
 * stops a dispatched pipeline run from stranding at {@code RUNNING} when a
 * processor never acknowledges the work order.
 */
public class WorkOrderResultRegistryTest {

	private static final long SHORT_TIMEOUT_MS = 300;

	@Test
	@DisplayName("A work order that is never acknowledged times out with a FAILED result")
	void testTimeoutProducesFailedResult() throws Exception {
		WorkOrderResultRegistry registry = new WorkOrderResultRegistry();
		UUID workOrderId = UUID.randomUUID();
		CompletableFuture<WorkOrderResult> received = new CompletableFuture<>();

		registry.registerWithTimeout(workOrderId, received::complete, SHORT_TIMEOUT_MS);

		WorkOrderResult result = received.get(5, TimeUnit.SECONDS);
		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.FAILED);
		assertThat(result.getWorkOrderId()).isEqualTo(workOrderId);
		assertThat(result.getErrorMessage()).contains("timed out");
		assertThat(registry.pendingCount())
			.as("the callback must be removed once it has timed out")
			.isZero();
	}

	@Test
	@DisplayName("An acknowledgement before the timeout wins and fires exactly once")
	void testAckBeforeTimeoutWins() throws Exception {
		WorkOrderResultRegistry registry = new WorkOrderResultRegistry();
		UUID workOrderId = UUID.randomUUID();
		AtomicInteger invocations = new AtomicInteger();
		CompletableFuture<WorkOrderResult> received = new CompletableFuture<>();

		registry.registerWithTimeout(workOrderId, r -> {
			invocations.incrementAndGet();
			received.complete(r);
		}, SHORT_TIMEOUT_MS);

		boolean routed = registry.complete(new WorkOrderResult()
			.setWorkOrderId(workOrderId)
			.setStatus(WorkOrderStatus.COMPLETED));

		assertThat(routed).isTrue();
		assertThat(received.get(1, TimeUnit.SECONDS).getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);

		// Wait past the timeout window — the watchdog must not fire a second time.
		Thread.sleep(SHORT_TIMEOUT_MS * 3);
		assertThat(invocations.get())
			.as("a completed work order must not also be timed out")
			.isEqualTo(1);
	}

	@Test
	@DisplayName("A cancelled work order neither times out nor routes")
	void testCancelSuppressesTimeout() throws Exception {
		WorkOrderResultRegistry registry = new WorkOrderResultRegistry();
		UUID workOrderId = UUID.randomUUID();
		AtomicInteger invocations = new AtomicInteger();

		registry.registerWithTimeout(workOrderId, r -> invocations.incrementAndGet(), SHORT_TIMEOUT_MS);
		registry.cancel(workOrderId);

		Thread.sleep(SHORT_TIMEOUT_MS * 3);
		assertThat(invocations.get()).isZero();
		assertThat(registry.pendingCount()).isZero();
	}

	@Test
	@DisplayName("Completing an unknown work order reports that nothing was routed")
	void testCompleteUnknownWorkOrder() {
		WorkOrderResultRegistry registry = new WorkOrderResultRegistry();

		boolean routed = registry.complete(new WorkOrderResult()
			.setWorkOrderId(UUID.randomUUID())
			.setStatus(WorkOrderStatus.COMPLETED));

		assertThat(routed).isFalse();
	}

	@Test
	@DisplayName("A null result or missing work-order id is handled without throwing")
	void testMalformedResults() {
		WorkOrderResultRegistry registry = new WorkOrderResultRegistry();

		assertThat(registry.complete(null)).isFalse();
		assertThat(registry.complete(new WorkOrderResult().setStatus(WorkOrderStatus.COMPLETED))).isFalse();
	}

	@Test
	@DisplayName("A zero or negative timeout registers the callback without a watchdog")
	void testNoTimeoutWhenNonPositive() throws Exception {
		WorkOrderResultRegistry registry = new WorkOrderResultRegistry();
		UUID workOrderId = UUID.randomUUID();
		AtomicInteger invocations = new AtomicInteger();

		registry.registerWithTimeout(workOrderId, r -> invocations.incrementAndGet(), 0);

		Thread.sleep(SHORT_TIMEOUT_MS);
		assertThat(invocations.get()).isZero();
		assertThat(registry.pendingCount())
			.as("the callback stays registered indefinitely when no timeout is set")
			.isEqualTo(1);
	}
}
