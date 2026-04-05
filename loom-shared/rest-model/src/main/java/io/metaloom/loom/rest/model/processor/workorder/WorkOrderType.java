package io.metaloom.loom.rest.model.processor.workorder;

/**
 * Types of work orders that loom can dispatch to processor nodes.
 */
public enum WorkOrderType {

	/**
	 * Run fingerprint action on a batch of assets.
	 */
	FINGERPRINT,

	/**
	 * Run a filesystem scan on an asset/storage location.
	 */
	FILESYSTEM_SCAN
}
