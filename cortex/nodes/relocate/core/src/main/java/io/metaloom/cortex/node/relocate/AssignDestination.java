package io.metaloom.cortex.node.relocate;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomClient;

/**
 * Resolves and writes one kind of membership.
 *
 * <p>
 * Deliberately narrow: resolve a target, ask whether the asset is already in it, and link it. Everything a membership needs and nothing a file needs -
 * there is no path on this interface, and that is the point.
 * </p>
 */
public interface AssignDestination {

	AssignTarget target();

	default List<String> validate(AssignNodeOptions options) {
		return List.of();
	}

	/**
	 * Find the target, creating it only when {@code onMissing} says to.
	 *
	 * @param client
	 * @param options
	 * @return the target's uuid, or null when it does not exist and must not be created
	 * @throws Exception
	 */
	UUID resolve(LoomClient client, AssignNodeOptions options) throws Exception;

	boolean isMember(LoomClient client, UUID targetUuid, UUID assetUuid) throws Exception;

	void link(LoomClient client, UUID targetUuid, UUID assetUuid) throws Exception;

	/**
	 * The table the ledger's {@code result_ref} should name.
	 *
	 * <p>
	 * The join row itself has no uuid - {@code collection_asset} is keyed on the pair - so the reference points at the container rather than the
	 * membership.
	 * </p>
	 */
	String table();

	/** A human-readable name for the resolved target, for skip and failure messages. */
	String describe(AssignNodeOptions options);
}
