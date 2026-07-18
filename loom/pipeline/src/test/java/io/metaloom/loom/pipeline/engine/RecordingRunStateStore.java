package io.metaloom.loom.pipeline.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.metaloom.loom.pipeline.engine.ItemState.ItemOutcome;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;

/**
 * A {@link RunStateStore} that records what it was told, so tests can assert that the
 * engine reports enough for a restart to resume from.
 */
public class RecordingRunStateStore implements RunStateStore {

	public record Discovered(UUID itemUuid, long seq, MediaRef media) {
	}

	public record Settled(UUID itemUuid, String nodeId, NodeTaskResult result) {
	}

	public final List<Discovered> discovered = new ArrayList<>();
	public final List<NodeTask> dispatched = new ArrayList<>();
	public final List<Settled> settled = new ArrayList<>();
	public final Map<UUID, ItemOutcome> itemOutcomes = new LinkedHashMap<>();
	public int flushCount;

	@Override
	public UUID itemDiscovered(UUID runUuid, long itemSeq, MediaRef media) {
		UUID itemUuid = UUID.randomUUID();
		discovered.add(new Discovered(itemUuid, itemSeq, media));
		return itemUuid;
	}

	@Override
	public void taskDispatched(UUID itemUuid, NodeTask task) {
		dispatched.add(task);
	}

	@Override
	public void taskSettled(UUID itemUuid, NodeTaskResult result) {
		settled.add(new Settled(itemUuid, result.getNodeId(), result));
	}

	@Override
	public void itemSettled(UUID itemUuid, ItemOutcome outcome) {
		itemOutcomes.put(itemUuid, outcome);
	}

	@Override
	public void flush() {
		flushCount++;
	}

	/** @return the node ids settled against the given item, in order */
	public List<String> settledNodes(UUID itemUuid) {
		return settled.stream().filter(s -> itemUuid.equals(s.itemUuid())).map(Settled::nodeId).toList();
	}

}
