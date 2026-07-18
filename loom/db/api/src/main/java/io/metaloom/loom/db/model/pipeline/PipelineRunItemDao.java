package io.metaloom.loom.db.model.pipeline;

import java.util.List;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.page.Page;

public interface PipelineRunItemDao extends CRUDDao<PipelineRunItem> {

	/**
	 * Load every item of a run, in discovery order.
	 *
	 * <p>⚠️ A run can hold hundreds of thousands of items. Prefer
	 * {@link #loadPageByRun} for anything user-facing; this exists for recovery,
	 * which needs the whole set.</p>
	 */
	List<PipelineRunItem> loadByRun(UUID runUuid);

	Page<PipelineRunItem> loadPageByRun(UUID runUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection);

	/**
	 * Load the items of a run that have not reached a terminal state.
	 *
	 * <p>This is what recovery resumes: everything else is already decided.</p>
	 */
	List<PipelineRunItem> loadUnfinishedByRun(UUID runUuid);

	/**
	 * @param runUuid the run
	 * @param state   one of PENDING, RUNNING, SUCCESS, FAILED, SKIPPED
	 * @return how many items of the run are in that state
	 */
	long countByRunAndState(UUID runUuid, String state);

	/**
	 * Create an unsaved item.
	 *
	 * @param userUuid  who owns the run
	 * @param runUuid   the run
	 * @param itemSeq   discovery order within the run
	 * @param mediaPath absolute path reported by the source
	 */
	PipelineRunItem createRunItem(UUID userUuid, UUID runUuid, long itemSeq, String mediaPath);

}
