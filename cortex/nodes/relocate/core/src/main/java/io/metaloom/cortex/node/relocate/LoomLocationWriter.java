package io.metaloom.cortex.node.relocate;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryFilesystemInfo;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryUpdateRequest;
import io.metaloom.loom.rest.model.asset.binary.AssetS3Meta;
import io.metaloom.loom.rest.model.asset.info.FileInfo;

/**
 * Telling Loom where the bytes went.
 *
 * <p>
 * 🔴 One fact, two places. The location of an asset's bytes is recorded on {@code asset_location.path} <b>and</b>, in practice, on
 * {@code asset.filename} - which holds an absolute path, because both dedup nodes do {@code new File(asset.getFile().getFilename()).exists()} on it.
 * Nothing reconciles the two, and nothing kept either of them current after the dedup nodes moved a file: leaving {@code asset.filename} stale makes
 * the "does the keeper still exist" safeguard fail for every moved file, which silently disables dedup apply.
 * </p>
 *
 * <p>
 * Writing both is a patch, not a fix - the real fix is to stop giving {@code asset.filename} path semantics - but a mover that updates only one of
 * them is worse than one that updates neither, because it makes the disagreement look deliberate.
 * </p>
 */
@Singleton
public class LoomLocationWriter {

	private static final Logger log = LoggerFactory.getLogger(LoomLocationWriter.class);

	@Inject
	public LoomLocationWriter() {
	}

	/**
	 * Record the new location.
	 *
	 * @param client
	 * @param asset
	 * @param plan
	 *            where the bytes now are
	 * @param previousLocator
	 *            where they were, used to pick the right binary when the asset has several
	 * @return the uuid of the binary that was updated, or null when none could be
	 */
	public UUID record(LoomClient client, AssetResponse asset, MovePlan plan, String previousLocator) {
		if (client == null || asset == null) {
			return null;
		}
		UUID binaryUuid = updateBinary(client, asset, plan, previousLocator);
		updateAssetFilename(client, asset, plan);
		return binaryUuid;
	}

	private UUID updateBinary(LoomClient client, AssetResponse asset, MovePlan plan, String previousLocator) {
		try {
			AssetBinaryResponse binary = selectBinary(client, asset, previousLocator);
			if (binary == null) {
				log.warn("Asset {} has no binary record to re-point at {}", asset.getUuid(), plan.locator());
				return null;
			}

			AssetBinaryUpdateRequest request = new AssetBinaryUpdateRequest();
			if (plan.kind() == MovePlan.Kind.S3) {
				request.setS3(new AssetS3Meta().setBucket(plan.bucket()).setObjectPath(plan.key()));
			} else {
				request.setFilesystem(new AssetBinaryFilesystemInfo().setPath(plan.targetPath().toAbsolutePath().toString()));
			}
			// Only sent when the target names one; a plain folder move leaves both alone rather than
			// asserting a library and pool it knows nothing about.
			request.setLibraryUuid(plan.libraryUuid());
			request.setPoolUuid(plan.poolUuid());

			client.updateBinary(binary.getUuid(), request).sync();
			return binary.getUuid();
		} catch (Exception e) {
			log.warn("Failed to record the new location of asset {} at {}: {}", asset.getUuid(), plan.locator(), e.getMessage());
			return null;
		}
	}

	/**
	 * Pick the binary this move actually relocated.
	 *
	 * <p>
	 * An asset legitimately has several since V2.48 relaxed the unique constraint to {@code (library_uuid, path)} - one per library it was imported
	 * into. Matching on the path the bytes came from is the only way to re-point the right one; falling back to the primary is better than refusing,
	 * because the single-binary case is overwhelmingly the common one.
	 * </p>
	 */
	private AssetBinaryResponse selectBinary(LoomClient client, AssetResponse asset, String previousLocator) throws Exception {
		List<AssetBinaryResponse> binaries = client.listAssetBinaries(asset.getUuid()).sync().body().getData();
		if (binaries == null || binaries.isEmpty()) {
			return null;
		}
		if (previousLocator != null) {
			for (AssetBinaryResponse binary : binaries) {
				if (previousLocator.equals(pathOf(binary))) {
					return binary;
				}
			}
		}
		return binaries.get(0);
	}

	private String pathOf(AssetBinaryResponse binary) {
		if (binary.getFilesystem() != null) {
			return binary.getFilesystem().getPath();
		}
		return null;
	}

	/**
	 * Keep {@code asset.filename} in step - for filesystem destinations only.
	 *
	 * <p>
	 * ⚠️ Deliberately not written for an S3 destination. The readers of this field treat it as a local path and call {@code new File(...).exists()} on
	 * it; storing {@code s3://...} there would make that answer false everywhere, which is a worse lie than a stale path.
	 * </p>
	 */
	private void updateAssetFilename(LoomClient client, AssetResponse asset, MovePlan plan) {
		if (plan.kind() != MovePlan.Kind.FILESYSTEM) {
			return;
		}
		try {
			FileInfo file = new FileInfo().setFilename(plan.targetPath().toAbsolutePath().toString());
			client.updateAsset(asset.getUuid(), new AssetUpdateRequest().setFile(file)).sync();
		} catch (Exception e) {
			log.warn("Failed to update the filename of asset {} to {}: {}", asset.getUuid(), plan.locator(), e.getMessage());
		}
	}
}
