package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.model.noderun.NodeProbeRequest;
import io.metaloom.loom.rest.model.noderun.NodeProbeResponse;
import io.metaloom.loom.rest.model.noderun.NodeRunListResponse;
import io.metaloom.loom.rest.model.noderun.NodeRunRequest;
import io.metaloom.loom.rest.model.noderun.NodeRunResponse;
import io.metaloom.loom.rest.model.noderun.NodeRunStatusResponse;

/**
 * Running processing nodes on chosen assets without a stored pipeline.
 *
 * <p>
 * Every method here requires {@code EXECUTE_MCP_NODE} and is scoped to the caller: an ad-hoc run
 * belongs to whoever started it and is not reachable through the pipeline routes.
 * </p>
 */
public interface NodeRunMethods {

	/**
	 * Run one node against one asset and wait for the result.
	 *
	 * <p>
	 * A node that cannot be run reports why in {@link NodeProbeResponse#getMessage()} rather than
	 * failing the request - the request was well formed, the answer is just "not this node".
	 * </p>
	 */
	LoomClientRequest<NodeProbeResponse> probeNode(NodeProbeRequest request);

	/**
	 * Start an ad-hoc node run and get a handle back immediately.
	 *
	 * <p>
	 * The work continues after the call returns; poll it with {@link #loadNodeRun(UUID)}.
	 * </p>
	 */
	LoomClientRequest<NodeRunResponse> startNodeRun(NodeRunRequest request);

	/** The caller's own ad-hoc node runs, newest first. */
	LoomClientRequest<NodeRunListResponse> listNodeRuns();

	/** Status and per-item results of one of the caller's ad-hoc node runs. */
	LoomClientRequest<NodeRunStatusResponse> loadNodeRun(UUID runUuid);

	/** Stop one of the caller's ad-hoc node runs. */
	LoomClientRequest<GenericMessageResponse> cancelNodeRun(UUID runUuid);

}
