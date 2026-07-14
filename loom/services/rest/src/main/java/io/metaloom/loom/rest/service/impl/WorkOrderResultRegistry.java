package io.metaloom.loom.rest.service.impl;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.model.processor.workorder.WorkOrderResult;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderStatus;

/**
 * Registry that maps work-order UUIDs to completion callbacks so that
 * callers who dispatch work orders can be notified when the processor
 * reports a {@link WorkOrderResult}.
 *
 * <p>The {@link ProcessorEndpoint} calls {@link #complete(WorkOrderResult)}
 * when it receives a {@code WORK_ORDER_RESULT} message from a processor.
 * If a callback is registered for that work-order ID, it is invoked and
 * then removed. Callers can use {@link #register(UUID, Consumer)} to
 * await the result, optionally with a timeout via
 * {@link #registerWithTimeout(UUID, Consumer, long)}.</p>
 */
@Singleton
public class WorkOrderResultRegistry {

	private static final Logger log = LoggerFactory.getLogger(WorkOrderResultRegistry.class);

	private final ConcurrentHashMap<UUID, Consumer<WorkOrderResult>> callbacks = new ConcurrentHashMap<>();

	@Inject
	public WorkOrderResultRegistry() {
	}

	/**
	 * Register a callback to be invoked when the work-order result arrives.
	 *
	 * @param workOrderId the work-order UUID to watch
	 * @param callback    the action to run when the result is received
	 */
	public void register(UUID workOrderId, Consumer<WorkOrderResult> callback) {
		callbacks.put(workOrderId, callback);
	}

	/**
	 * Register a callback with a timeout. If no result arrives within the
	 * specified delay, the callback is invoked with a failed
	 * {@link WorkOrderResult} (status = FAILED) and removed.
	 *
	 * @param workOrderId the work-order UUID to watch
	 * @param callback    the action to run when the result is received or timed out
	 * @param timeoutMs   timeout in milliseconds (<= 0 means no timeout)
	 */
	public void registerWithTimeout(UUID workOrderId, Consumer<WorkOrderResult> callback, long timeoutMs) {
		callbacks.put(workOrderId, callback);
		if (timeoutMs > 0) {
			Thread.ofVirtual().start(() -> {
				try {
					Thread.sleep(timeoutMs);
				} catch (InterruptedException e) {
					return;
				}
				Consumer<WorkOrderResult> existing = callbacks.remove(workOrderId);
				if (existing != null) {
					log.warn("Work order {} timed out after {} ms", workOrderId, timeoutMs);
					WorkOrderResult timeoutResult = new WorkOrderResult()
						.setWorkOrderId(workOrderId)
						.setStatus(WorkOrderStatus.FAILED)
						.setErrorMessage("Work order timed out after " + timeoutMs + " ms");
					existing.accept(timeoutResult);
				}
			});
		}
	}

	/**
	 * Complete a work order. If a callback is registered for the given
	 * work-order ID, invoke it and remove the entry.
	 *
	 * @param result the result reported by the processor
	 * @return true if a callback was found and invoked
	 */
	public boolean complete(WorkOrderResult result) {
		if (result == null || result.getWorkOrderId() == null) {
			return false;
		}
		Consumer<WorkOrderResult> callback = callbacks.remove(result.getWorkOrderId());
		if (callback != null) {
			callback.accept(result);
			return true;
		}
		return false;
	}

	/**
	 * Remove a registered callback without invoking it (e.g. caller gave up).
	 *
	 * @param workOrderId the work-order UUID to cancel
	 */
	public void cancel(UUID workOrderId) {
		callbacks.remove(workOrderId);
	}

	/**
	 * Return the number of pending work-order callbacks.
	 */
	public int pendingCount() {
		return callbacks.size();
	}
}
