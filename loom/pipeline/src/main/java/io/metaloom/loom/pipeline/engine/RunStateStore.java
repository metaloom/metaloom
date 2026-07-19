package io.metaloom.loom.pipeline.engine;

import java.util.UUID;

import io.metaloom.loom.pipeline.engine.ItemState.ItemOutcome;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;

/**
 * Where a run's execution state is recorded so it can outlive the process.
 *
 * <p>Phase 1 kept every item and node result in the engine's heap, so a Loom restart
 * stranded in-flight work with no way to tell what had already been done. This is
 * the seam that fixes that: the engine reports what it decides, and an
 * implementation decides how durable that is.</p>
 *
 * <h2>Why the store assigns item identity</h2>
 *
 * <p>{@link #itemDiscovered} returns the id rather than accepting one. Identity has
 * to come from whatever survives a restart - otherwise a recovered run cannot match
 * an arriving {@code NODE_TASK_RESULT} to the item it belongs to, because the
 * engine's in-memory counter starts again from zero.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>The engine serialises all calls, so an implementation needs no locking of its
 * own. Calls happen while the engine holds its monitor, so an implementation must
 * not block for long - buffer and flush rather than writing a row per call.</p>
 */
public interface RunStateStore {

	/**
	 * Record that the source enumerated an item.
	 *
	 * @param runUuid the run
	 * @param itemSeq discovery order within the run, unique per run
	 * @param media   the discovered item
	 * @return the id this item is known by for the rest of the run, never null
	 */
	UUID itemDiscovered(UUID runUuid, long itemSeq, MediaRef media);

	/**
	 * Record that a node became ready and was handed to a worker.
	 *
	 * @param itemUuid the item
	 * @param task     the dispatched task
	 */
	void taskDispatched(UUID itemUuid, NodeTask task);

	/**
	 * Record a node's terminal outcome, whether it ran, was skipped, or failed.
	 *
	 * @param itemUuid the item
	 * @param result   the outcome
	 */
	void taskSettled(UUID itemUuid, NodeTaskResult result);

	/**
	 * Record that every node of an item has reached a terminal state.
	 *
	 * @param itemUuid the item
	 * @param outcome  how the item as a whole turned out
	 */
	void itemSettled(UUID itemUuid, ItemOutcome outcome);

	/**
	 * Record that the source finished enumerating.
	 *
	 * <p>Recovery needs this to tell two very different situations apart: a run whose
	 * source completed can be finished faithfully, whereas one that was still scanning
	 * has media that were never recorded anywhere and cannot be.</p>
	 *
	 * @param runUuid    the run
	 * @param totalCount how many items the source produced
	 */
	void sourceCompleted(UUID runUuid, long totalCount);

	/**
	 * Push any buffered writes.
	 *
	 * <p>Called when a run completes. An implementation that batches <em>must</em>
	 * drain here, or the tail of a run is lost precisely when it matters.</p>
	 */
	void flush();

	/**
	 * A store that remembers nothing.
	 *
	 * <p>Preserves Phase 1 behaviour exactly, which is what keeps the engine testable
	 * without a database. Item ids are random, since nothing will look them up
	 * again.</p>
	 */
	RunStateStore NOOP = new RunStateStore() {

		@Override
		public UUID itemDiscovered(UUID runUuid, long itemSeq, MediaRef media) {
			return UUID.randomUUID();
		}

		@Override
		public void taskDispatched(UUID itemUuid, NodeTask task) {
		}

		@Override
		public void taskSettled(UUID itemUuid, NodeTaskResult result) {
		}

		@Override
		public void itemSettled(UUID itemUuid, ItemOutcome outcome) {
		}

		@Override
		public void sourceCompleted(UUID runUuid, long totalCount) {
		}

		@Override
		public void flush() {
		}
	};

}
