package io.metaloom.loom.rest.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.page.Page;

/**
 * A {@link PipelineNodeTaskDao} that serves a fixed list of expired leases and
 * records updates.
 *
 * <p>Only the two methods the reaper actually uses are implemented. The rest throw,
 * so a future change that starts calling one of them fails loudly here instead of
 * quietly getting an empty answer.</p>
 */
public class FakeNodeTaskDao implements PipelineNodeTaskDao {

	/** Returned by {@link #loadExpiredLeases}. */
	public final List<PipelineNodeTask> expired = new ArrayList<>();

	/** Returned by {@link #loadLeasedBy}, keyed on {@link PipelineNodeTask#getLeasedBy()}. */
	public final List<PipelineNodeTask> leased = new ArrayList<>();

	/** Everything passed to {@link #update}. */
	public final List<PipelineNodeTask> updated = new ArrayList<>();

	@Override
	public List<PipelineNodeTask> loadExpiredLeases(Instant now, int limit) {
		return expired.size() > limit ? new ArrayList<>(expired.subList(0, limit)) : new ArrayList<>(expired);
	}

	@Override
	public List<PipelineNodeTask> loadLeasedBy(String processorNodeId, int limit) {
		return leased.stream()
			.filter(task -> processorNodeId.equals(task.getLeasedBy()))
			.limit(limit)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
	}

	@Override
	public PipelineNodeTask update(PipelineNodeTask element) {
		updated.add(element);
		return element;
	}

	@Override
	public List<PipelineNodeTask> loadByItem(UUID itemUuid) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public List<PipelineNodeTask> loadByRun(UUID runUuid) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public Page<PipelineNodeTask> loadPageByRun(UUID runUuid, UUID fromId, int pageSize, List<Filter> filters,
		SortKey sortBy, SortDirection sortDirection) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public PipelineNodeTask loadByItemAndNode(UUID itemUuid, String nodeId, int elementSeq) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public PipelineNodeTask loadByItemAndNode(UUID itemUuid, String nodeId, int elementSeq, int generation) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public long countByRunAndState(UUID runUuid, NodeTaskState state) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public long countLeasedBy(String processorNodeId) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public PipelineNodeTask createNodeTask(UUID userUuid, UUID itemUuid, UUID runUuid, String nodeId, String nodeKind) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public void delete(UUID id) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public PipelineNodeTask load(UUID id) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public void store(PipelineNodeTask element) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public Page<PipelineNodeTask> loadPage(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public Stream<? extends PipelineNodeTask> findAll() {
		throw new UnsupportedOperationException("Not used by the reaper");
	}

	@Override
	public long count() {
		return expired.size();
	}

	@Override
	public String getTypeName() {
		return "Fake Pipeline Node Tasks";
	}

	@Override
	public void clear() {
		expired.clear();
		leased.clear();
		updated.clear();
	}

}
