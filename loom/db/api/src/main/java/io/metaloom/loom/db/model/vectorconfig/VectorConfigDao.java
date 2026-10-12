package io.metaloom.loom.db.model.vectorconfig;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;

public interface VectorConfigDao extends CRUDDao<VectorConfig> {

	/**
	 * Build - but do not store - a vector config.
	 *
	 * @param creatorUuid
	 * @param name
	 *            unique across the installation
	 * @return
	 */
	VectorConfig createVectorConfig(UUID creatorUuid, String name);

	/**
	 * Load the config by its unique name.
	 *
	 * @param name
	 * @return The config, or null when there is none of that name.
	 */
	VectorConfig loadByName(String name);

}
