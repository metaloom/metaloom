package io.metaloom.loom.rest.service.impl;

import java.time.Instant;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.model.asset.AssetNodeResult;
import io.metaloom.loom.db.model.asset.AssetNodeResultDao;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.vertx.core.json.JsonObject;

/**
 * Records an ad-hoc node result in the per-asset processing ledger, on request.
 *
 * <h2>Why the node id is namespaced</h2>
 *
 * <p>
 * {@code asset_node_result} is {@code UNIQUE (asset_uuid, node_kind, node_id)} and
 * {@link AssetNodeResultDao#upsert} rewrites the matching row. An ad-hoc run that used the graph-local
 * node id from its definition - {@code "vlm"}, say - would therefore overwrite whatever a scheduled
 * pipeline had recorded under that same id, and the overwrite would be invisible: same asset, same
 * kind, plausible new result. Writing under {@link AdhocRuns#nodeResultId(UUID)} makes the collision
 * impossible rather than unlikely, because a valid graph node id cannot contain a colon. It is also
 * the provenance marker - {@code origin} cannot carry one, its CHECK constraint allows only
 * {@code COMPUTED}, {@code LOCAL} and {@code REMOTE}.
 * </p>
 *
 * <h2>Why a probe writes no run reference</h2>
 *
 * <p>
 * {@code run_uuid} and {@code task_uuid} are real foreign keys. An asynchronous run has both rows and
 * can point at them; a <em>probe</em> has neither - it runs against {@code RunStateStore.NOOP} and its
 * run uuid exists only to route results back through the registry - so pointing at them would violate
 * the constraint and lose the ledger row entirely. Hence the two entry points below.
 * </p>
 *
 * <h2>What this cannot protect against</h2>
 *
 * <p>
 * Several node kinds write to Loom themselves through {@code LoomClient} while they run. Declining to
 * call this writer stops <em>Loom</em> recording anything; it cannot stop a worker that already sent
 * its own REST request. That is why those kinds are excluded from probing entirely - see
 * {@link ProbeEligibility} and {@code LOOM_AGENT_PROBE_DENY_KINDS}.
 * </p>
 */
@Singleton
public class AdhocNodeResultWriter {

	private static final Logger log = LoggerFactory.getLogger(AdhocNodeResultWriter.class);

	/**
	 * The node computed this result here and now.
	 *
	 * <p>
	 * The {@code origin} CHECK constraint accepts only {@code COMPUTED}, {@code LOCAL} and
	 * {@code REMOTE}, so "this came from an agent" cannot be expressed here. The {@code adhoc:} node id
	 * prefix carries that instead, and is queryable.
	 * </p>
	 */
	public static final String ORIGIN_COMPUTED = "COMPUTED";

	// The three values asset_node_result.state accepts. Its CHECK constraint is the authority; these
	// mirror it so a typo fails to compile rather than at insert time.
	public static final String STATE_SUCCESS = "SUCCESS";
	public static final String STATE_SKIPPED = "SKIPPED";
	public static final String STATE_FAILED = "FAILED";

	private final AssetNodeResultDao dao;

	@Inject
	public AdhocNodeResultWriter(AssetNodeResultDao dao) {
		this.dao = dao;
	}

	/**
	 * Record the result of a synchronous probe.
	 *
	 * <p>
	 * No {@code run_uuid} and no {@code task_uuid}: a probe persists neither, so both foreign keys have
	 * nothing to point at. The result itself is the whole record.
	 * </p>
	 *
	 * @param probeRunUuid the probe's synthetic run uuid, used only to namespace the node id
	 */
	public void writeProbe(UUID probeRunUuid, UUID userUuid, UUID assetUuid, String nodeKind, NodeTaskResult result) {
		if (result == null) {
			return;
		}
		write(probeRunUuid, null, null, userUuid, assetUuid, nodeKind, ledgerState(result.getState()),
			result.getDurationMs(), result.getMessage(), NodeResultRenderer.renderOutputs(result.getOutputs()));
	}

	/**
	 * Record one settled task of an asynchronous run.
	 *
	 * <p>
	 * A run records its ledger at the end, from the rows it already wrote, rather than as each node
	 * settles: the settle callback carries no outputs and fires on the engine thread with its monitor
	 * held, which is the wrong place to start issuing database writes.
	 * </p>
	 */
	public void writeTask(UUID runUuid, UUID userUuid, UUID assetUuid, PipelineNodeTask task, JsonObject resultRef) {
		if (task == null) {
			return;
		}
		write(runUuid, runUuid, task.getUuid(), userUuid, assetUuid, task.getNodeKind(), ledgerState(task.getState()),
			task.getDurationMs(), task.getErrorMessage(), resultRef);
	}

	private void write(UUID namespaceUuid, UUID runRef, UUID taskRef, UUID userUuid, UUID assetUuid, String nodeKind,
		String state, Long durationMs, String message, JsonObject resultRef) {
		if (assetUuid == null || nodeKind == null) {
			return;
		}
		try {
			AssetNodeResult entry = dao.createNodeResult(userUuid, assetUuid, nodeKind, AdhocRuns.nodeResultId(namespaceUuid));
			entry.setState(state);
			entry.setOrigin(ORIGIN_COMPUTED);
			entry.setRunUuid(runRef);
			entry.setTaskUuid(taskRef);
			entry.setDurationMs(durationMs);
			entry.setFinished(Instant.now());
			entry.setReason(message);
			// Advisory, not a foreign key: it says what the node produced so a later reader can decide
			// whether it is worth recomputing, without this table growing a copy of every payload.
			entry.setResultRef(resultRef);
			dao.upsert(entry);
		} catch (Exception e) {
			// Never allowed to fail the run that produced the result: the work really happened, and
			// losing the bookkeeping is a smaller problem than reporting a completed node as failed.
			log.error("Failed to record ad-hoc node result for asset {} kind {} (run {})", assetUuid, nodeKind, namespaceUuid, e);
		}
	}

	/**
	 * Translate an execution state into a ledger state.
	 *
	 * <p>
	 * These are two different vocabularies and the difference matters: an execution state describes a
	 * task's progress and includes {@code PENDING} and {@code RUNNING}, while
	 * {@code asset_node_result.state} is a verdict whose CHECK constraint accepts only {@code SUCCESS},
	 * {@code SKIPPED} and {@code FAILED} (see {@code V2.45__add_asset_node_result.sql}). Passing the
	 * execution name straight through is rejected by the database, so the mapping is done here rather
	 * than at each call site.
	 * </p>
	 *
	 * @param state the execution state, e.g. {@code COMPLETED}
	 */
	public static String ledgerState(Object state) {
		String name = String.valueOf(state);
		return switch (name) {
			case "COMPLETED", "SUCCESS" -> STATE_SUCCESS;
			case "SKIPPED" -> STATE_SKIPPED;
			// Anything that did not clearly succeed or skip is a failure as far as the catalog is
			// concerned. Recording an unfinished task as a verdict would be worse than saying it failed.
			default -> STATE_FAILED;
		};
	}

}
