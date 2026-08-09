package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.EXECUTE_MCP_NODE;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.model.noderun.NodeProbeRequest;
import io.metaloom.loom.rest.model.noderun.NodeRunListResponse;
import io.metaloom.loom.rest.model.noderun.NodeRunRequest;
import io.metaloom.loom.rest.model.noderun.NodeRunResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * HTTP surface for ad-hoc node execution.
 *
 * <p>
 * Deliberately thin: validation, quotas, dispatch and ownership scoping all live in
 * {@link NodeRunService}, because the MCP execution tools call the same service and neither surface
 * may end up enforcing a rule the other does not.
 * </p>
 *
 * <p>
 * Every route requires {@code EXECUTE_MCP_NODE}. Row-level access is not a permission question - an
 * ad-hoc run belongs to whoever started it - so a foreign run answers <b>404 rather than 403</b>, the
 * same rule the notification inbox follows: a 403 would confirm the uuid exists.
 * </p>
 */
@Singleton
public class NodeRunEndpointService extends AbstractEndpointService {

	/** A page size that shows a working session's jobs without a second request. */
	public static final int DEFAULT_PAGE_SIZE = 25;

	private final NodeRunService nodeRunService;

	@Inject
	public NodeRunEndpointService(NodeRunService nodeRunService, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.nodeRunService = nodeRunService;
	}

	/**
	 * Run one node against one asset and answer with the result.
	 *
	 * <p>
	 * Always 200 when the request itself was well formed. A node that could not be run reports why in
	 * the response body rather than as an HTTP error, because "vlm is not available right now" is an
	 * answer to the question that was asked.
	 * </p>
	 */
	public void probe(LoomRoutingContext lrc) {
		checkPerm(lrc, EXECUTE_MCP_NODE, () -> {
			NodeProbeRequest request = lrc.requestBody(NodeProbeRequest.class);
			nodeRunService.probe(lrc.userUuid(), request)
				.onSuccess(lrc::send)
				// A probe resolves its own refusals into a response, so reaching here means something
				// genuinely broke. Route it through the normal failure handler rather than letting the
				// request hang until the client gives up.
				.onFailure(lrc.routingContext()::fail);
		});
	}

	/** Start an ad-hoc run and answer with its handle. 202: the work has been accepted, not done. */
	public void start(LoomRoutingContext lrc) {
		checkPerm(lrc, EXECUTE_MCP_NODE, () -> {
			NodeRunRequest request = lrc.requestBody(NodeRunRequest.class);
			NodeRunResponse response = nodeRunService.startRun(lrc.userUuid(), request);
			lrc.send(response, 202);
		});
	}

	/** The caller's own ad-hoc runs, newest first. */
	public void list(LoomRoutingContext lrc) {
		checkPerm(lrc, EXECUTE_MCP_NODE, () -> {
			int pageSize = lrc.pageSize() > 0 ? lrc.pageSize() : DEFAULT_PAGE_SIZE;
			Page<PipelineRun> page = nodeRunService.list(lrc.userUuid(), lrc.pagingParams().from(), pageSize);

			NodeRunListResponse response = new NodeRunListResponse();
			UUID lastUuid = null;
			for (PipelineRun run : page) {
				// Without results: a listing is a status overview, and loading every task row for every
				// run on the page would make the list cost more than the runs it describes.
				response.add(nodeRunService.toStatus(run));
				lastUuid = run.getUuid();
			}
			PagingInfo metainfo = new PagingInfo();
			metainfo.setPerPage(page.perPage());
			// DAOs that cannot compute a total report TOTAL_COUNT_UNKNOWN; falling back to the page
			// size keeps the field from going negative on the wire.
			long totalCount = page.totalCount();
			metainfo.setTotalCount(totalCount == Page.TOTAL_COUNT_UNKNOWN ? page.size() : totalCount);
			metainfo.setLastUuid(lastUuid);
			response.setMetainfo(metainfo);
			lrc.send(response);
		});
	}

	/** Status and per-item results of one of the caller's runs. */
	public void load(LoomRoutingContext lrc, UUID runUuid) {
		checkPerm(lrc, EXECUTE_MCP_NODE, () -> {
			List<String> resultsParam = lrc.queryParam("results");
			// Results are the point of the route, so they are included unless explicitly declined.
			boolean includeResults = resultsParam.isEmpty() || Boolean.parseBoolean(resultsParam.get(0));
			lrc.send(nodeRunService.status(lrc.userUuid(), runUuid, includeResults));
		});
	}

	/** Stop one of the caller's runs. */
	public void cancel(LoomRoutingContext lrc, UUID runUuid) {
		checkPerm(lrc, EXECUTE_MCP_NODE, () -> {
			nodeRunService.cancel(lrc.userUuid(), runUuid);
			lrc.send(new GenericMessageResponse().setMessage("Node run cancelled"));
		});
	}

}
