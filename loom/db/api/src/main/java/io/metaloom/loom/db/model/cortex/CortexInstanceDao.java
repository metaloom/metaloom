package io.metaloom.loom.db.model.cortex;

import io.metaloom.loom.db.CRUDDao;

public interface CortexInstanceDao extends CRUDDao<CortexInstance> {

	/**
	 * Create a transient cortex instance for the given stable node id.
	 *
	 * @param nodeId stable worker identity
	 * @param name   human readable name
	 * @return the unsaved instance
	 */
	CortexInstance createCortexInstance(String nodeId, String name);

	/**
	 * Load the persisted cortex instance for a stable node id.
	 *
	 * @param nodeId stable worker identity
	 * @return the instance including its whitelist/blacklist, or null when unknown
	 */
	CortexInstance loadByNodeId(String nodeId);

	/**
	 * Insert or update the instance keyed by its {@link CortexInstance#getNodeId() node
	 * id}. When a row already exists it is updated (no duplicate row is created for the
	 * same node id); otherwise the instance is stored. The returned instance carries the
	 * persisted UUID and its whitelist/blacklist.
	 *
	 * @param instance the instance to persist
	 * @return the persisted instance
	 */
	CortexInstance upsertByNodeId(CortexInstance instance);

}
