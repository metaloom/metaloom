package io.metaloom.cortex.node.relocate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.fs.PathContainment;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.pool.AssetPoolResponse;

/**
 * A storage pool: either a filesystem root or an S3 bucket, never both.
 *
 * <p>
 * The pool row carries the discriminator ({@code fs_path} XOR {@code s3_bucket}, CHECK-enforced since V2.20), so resolving a pool is what turns "move
 * into storage X" into one of the node's two concrete write paths.
 * </p>
 *
 * <p>
 * The layout is forced to {@link Layout#CONTENT} regardless of what the node was configured with. A pool's whole purpose is that Loom can find the
 * bytes again, and Loom derives that location from the SHA-512, not from the file name.
 * </p>
 */
@Singleton
public class PoolDestination implements MoveDestination {

	@Inject
	public PoolDestination() {
	}

	@Override
	public MoveTarget target() {
		return MoveTarget.POOL;
	}

	@Override
	public List<String> validate(MoveNodeOptions options) {
		if (isBlank(options.getPoolUuid())) {
			return List.of("the POOL target needs a 'poolUuid'");
		}
		try {
			UUID.fromString(options.getPoolUuid().trim());
		} catch (IllegalArgumentException e) {
			return List.of("poolUuid is not a valid uuid: " + options.getPoolUuid());
		}
		return List.of();
	}

	@Override
	public MovePlan plan(LoomClient client, LoomMedia media, MoveNodeOptions options) throws Exception {
		requireClient(client, "POOL");
		return planForPool(client, UUID.fromString(options.getPoolUuid().trim()), media, options, null);
	}

	/**
	 * A pool lives in the database, so there is no offline answer. Failing is right where skipping would not be: the item was routed here on purpose,
	 * and reporting "nothing to do" would read as "this file did not need moving".
	 */
	static void requireClient(LoomClient client, String target) {
		if (client == null) {
			throw new IllegalStateException("The " + target + " target needs a Loom connection to resolve its storage pool, and this worker has none");
		}
	}

	/**
	 * Resolve the pool and turn it into a plan.
	 *
	 * @param client
	 *            must not be null - a pool can only be resolved through Loom
	 * @param poolUuid
	 * @param media
	 * @param options
	 * @param libraryUuid
	 *            recorded on the plan when the caller is the library destination, otherwise null
	 * @return
	 * @throws Exception
	 */
	public MovePlan planForPool(LoomClient client, UUID poolUuid, LoomMedia media, MoveNodeOptions options, UUID libraryUuid) throws Exception {
		AssetPoolResponse pool = client.loadPool(poolUuid).sync().body();
		if (pool == null) {
			throw new IllegalStateException("No storage pool found for uuid " + poolUuid);
		}

		if (!isBlank(pool.getS3Bucket())) {
			String key = Layouts.contentKey(media);
			return MovePlan.s3(pool.getS3Bucket(), key, libraryUuid, poolUuid);
		}

		if (isBlank(pool.getFsPath())) {
			throw new IllegalStateException(
				"Storage pool " + poolUuid + " declares neither a filesystem path nor an S3 bucket; it cannot hold anything");
		}

		Path root = Paths.get(pool.getFsPath()).toAbsolutePath().normalize();

		// 🔴 asset_pool.fs_path is a path on the Loom SERVER. This node writes from the worker. On a split
		// deployment the two are different machines, and writing to a worker-local directory of the same
		// name would put the bytes somewhere Loom will never look while reporting success. Fail loudly
		// instead: an operator can mount the pool root, or use an S3-backed pool, but they have to know.
		if (!Files.isDirectory(root)) {
			throw new IllegalStateException("Storage pool " + poolUuid + " resolves to " + root
				+ ", which is not a directory on this worker. Mount the pool root here, or use an S3-backed pool.");
		}

		if (PathContainment.isInside(media.file(), root)) {
			return MovePlan.filesystem(media.file().toPath(), libraryUuid, poolUuid).asAlreadyThere();
		}

		Path targetPath = Layouts.resolve(root, media, Layout.CONTENT, options.getSourceRoot());
		return MovePlan.filesystem(targetPath, libraryUuid, poolUuid);
	}

	static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
