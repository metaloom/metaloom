package io.metaloom.loom.db.model.library;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;

public interface Library extends CUDElement<Library> {

	String getName();

	Library setName(String name);

	/**
	 * The storage pool that binaries uploaded into this library are written to.
	 *
	 * <p>
	 * This is where "is a library filesystem- or S3-backed" is answered: the library names a pool, and the {@code asset_pool} row carries the
	 * discriminator ({@code fs_path} XOR {@code s3_bucket}, enforced by a CHECK constraint since {@code V2.20}). Duplicating the discriminator onto
	 * the library itself would have left two places to disagree.
	 * </p>
	 *
	 * @return the pool uuid, or null to use the process-wide local upload directory
	 */
	UUID getPoolUuid();

	Library setPoolUuid(UUID poolUuid);

}
