package io.metaloom.loom.db.model.nodes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;

/**
 * Storage for announced node contracts and the per-worker claims behind them.
 */
public interface NodeDescriptorRecordDao extends CRUDDao<NodeDescriptorRecord> {

	/**
	 * Create a transient record for a node type id.
	 *
	 * @param nodeId
	 *            the node type id
	 * @return the unsaved record, with its timestamps set (a machine writes these rows, so there is no
	 *         user to derive them from)
	 */
	NodeDescriptorRecord createNodeDescriptor(String nodeId);

	/**
	 * @param nodeId
	 *            the node type id
	 * @return the persisted contract, or null when this node type has never been announced
	 */
	NodeDescriptorRecord loadByNodeId(String nodeId);

	/**
	 * Insert or update keyed by node type id, so one node type never gets two rows.
	 */
	NodeDescriptorRecord upsertByNodeId(NodeDescriptorRecord record);

	/**
	 * @param nodeId
	 *            the node type id
	 * @return whether a row existed and was removed
	 */
	boolean deleteByNodeId(String nodeId);

	/**
	 * Replace one worker's whole claim set.
	 *
	 * <p>
	 * A replace rather than a merge, matching the wire format: there is no delta frame, so a node
	 * absent from a later announcement is unlinked and cannot leave a stale claim behind.
	 * </p>
	 *
	 * @param instanceUuid
	 *            the {@code cortex_instance} row for the announcing worker
	 * @param claims
	 *            node type id → {@code [version, bodyHash]}; version may be null
	 */
	void replaceClaims(UUID instanceUuid, Map<String, String[]> claims);

	/**
	 * @param nodeId
	 *            the node type id
	 * @return the {@code cortex_instance} uuids claiming to offer it
	 */
	Set<UUID> instancesClaiming(String nodeId);

	/**
	 * Every persisted contract, for rehydrating the ANNOUNCED registry layer at boot.
	 */
	List<? extends NodeDescriptorRecord> loadAll();
}
