package io.metaloom.loom.rest.service.impl;

import java.util.UUID;

import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.vertx.core.json.JsonObject;

/**
 * The two naming conventions ad-hoc runs depend on.
 *
 * <p>
 * Both exist because an ad-hoc run has no pipeline row to borrow an identity from, and both are used
 * from more than one place - recovery, event broadcasting, notifications and the asset ledger - so
 * they are defined once here rather than being re-derived and slowly diverging.
 * </p>
 */
public final class AdhocRuns {

	/**
	 * Prefix for the {@code asset_node_result.node_id} of a persisted ad-hoc result.
	 *
	 * <p>
	 * {@code asset_node_result} is {@code UNIQUE (asset_uuid, node_kind, node_id)}, so an ad-hoc run
	 * reusing a scheduled pipeline's node id would silently overwrite that pipeline's ledger row. A
	 * graph-local node id is validated against {@code ^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$} and can
	 * therefore never contain a colon, which makes this prefix a collision-proof namespace rather than
	 * a convention people have to remember. It also makes withdrawal a single predicate:
	 * {@code DELETE ... WHERE node_id LIKE 'adhoc:%'}.
	 * </p>
	 */
	public static final String NODE_ID_PREFIX = "adhoc:";

	/** How many characters of the run uuid go into the ledger node id. */
	private static final int RUN_ID_CHARS = 8;

	private AdhocRuns() {
	}

	/**
	 * A human-readable name for a run that has no pipeline.
	 *
	 * <p>
	 * Prefers the name the submitted definition carries, so a caller that labelled its graph sees that
	 * label in the events socket and in the completion notification; falls back to a short form of the
	 * run uuid, which is at least addressable.
	 * </p>
	 */
	public static String label(PipelineRun run) {
		JsonObject meta = run.getMeta();
		JsonObject definition = meta == null ? null : meta.getJsonObject(PipelineRun.META_DEFINITION);
		String name = definition == null ? null : definition.getString("name");
		if (name != null && !name.isBlank()) {
			return name;
		}
		return label(run.getUuid());
	}

	/** The fallback label for a run uuid, used before a row exists (a probe has none at all). */
	public static String label(UUID runUuid) {
		return "ad-hoc run " + shortId(runUuid);
	}

	/**
	 * The {@code asset_node_result.node_id} an ad-hoc result is recorded under.
	 *
	 * @param runUuid the run that produced the result; a probe passes its synthetic run uuid
	 */
	public static String nodeResultId(UUID runUuid) {
		return NODE_ID_PREFIX + shortId(runUuid);
	}

	private static String shortId(UUID runUuid) {
		return runUuid.toString().substring(0, RUN_ID_CHARS);
	}

}
