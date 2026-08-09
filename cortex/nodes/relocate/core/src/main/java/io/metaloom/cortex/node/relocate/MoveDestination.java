package io.metaloom.cortex.node.relocate;

import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.loom.client.common.LoomClient;

/**
 * Resolves where an item's bytes belong for one {@link MoveTarget}.
 *
 * <p>
 * A destination decides <b>where</b>; {@link MoveNode} performs <b>how</b>. That split is not tidiness: it means the two byte-writing paths - a local
 * filesystem and a bucket - each exist once, and that removing the source file happens in exactly one method of one class, reachable only after a
 * verification the destination itself does not get to skip.
 * </p>
 */
public interface MoveDestination {

	MoveTarget target();

	/**
	 * Config-time validation, called from {@code configure(...)} so a missing bucket fails the task rather than every item in it.
	 *
	 * @param options
	 * @return the problems found, empty when there are none
	 */
	default List<String> validate(MoveNodeOptions options) {
		return List.of();
	}

	/**
	 * Resolve the destination. Read-only: creates no directories, writes no bytes, and may be called for a dry run.
	 *
	 * @param client
	 *            null when the worker is offline. The folder target ignores it; every other target needs it to resolve a pool, and should say so
	 *            rather than guessing
	 * @param media
	 *            the item to relocate
	 * @param options
	 * @return the plan
	 * @throws Exception
	 *             when the destination cannot be resolved at all - an unknown pool, a pool root the worker cannot see, an unreachable Loom
	 */
	MovePlan plan(LoomClient client, LoomMedia media, MoveNodeOptions options) throws Exception;

	/**
	 * Mixed into the node's {@code producerVersion}, so a ledger row records which flavour of destination produced it.
	 */
	default String version() {
		return target().name().toLowerCase(java.util.Locale.ROOT) + "/1";
	}
}
