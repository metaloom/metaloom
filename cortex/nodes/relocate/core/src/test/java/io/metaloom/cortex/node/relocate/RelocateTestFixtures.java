package io.metaloom.cortex.node.relocate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import javax.inject.Provider;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.fs.LocalMover;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * Shared scaffolding for the relocation tests.
 */
public final class RelocateTestFixtures {

	/** A valid SHA-512, so the content-addressed layout has something to key on. */
	public static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	public static final UUID ASSET_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private RelocateTestFixtures() {
	}

	public static StubLoomMedia mediaWith(File dir, String name, String content) throws IOException {
		Path file = Files.writeString(dir.toPath().resolve(name), content);
		StubLoomMedia media = new StubLoomMedia(file.toAbsolutePath().toString(), false, true, false, false);
		media.setSHA512(HASH);
		return media;
	}

	public static AssetResponse asset() {
		return new AssetResponse().setUuid(ASSET_UUID);
	}

	public static CortexOptions cortexOptions(File dir) {
		return new CortexOptions().setMetaPath(dir.toPath());
	}

	/**
	 * A move node wired with just the folder destination, which is all the offline tests need.
	 */
	public static MoveNode folderNode(LoomClient client, CortexOptions cortexOptions, MoveNodeOptions options, LocalMover mover) {
		Map<MoveTarget, Provider<MoveDestination>> destinations = Map.of(MoveTarget.FOLDER, FolderDestination::new);
		return new MoveNode(client, cortexOptions, options, destinations, new LoomLocationWriter(), S3Support.inactive(), mover);
	}

	public static MoveNode folderNode(LoomClient client, CortexOptions cortexOptions, MoveNodeOptions options) {
		return folderNode(client, cortexOptions, options, new LocalMover());
	}

	/**
	 * A move node that believes every destination is on a different filesystem, so the copy paths can be exercised without a second mount point.
	 */
	public static MoveNode crossDeviceFolderNode(LoomClient client, CortexOptions cortexOptions, MoveNodeOptions options) {
		return folderNode(client, cortexOptions, options, new LocalMover((a, b) -> false));
	}

	public static MoveNodeOptions folderOptions(File targetFolder) {
		return new MoveNodeOptions()
			.setTarget(MoveTarget.FOLDER)
			.setTargetFolder(targetFolder.toPath())
			.setLayout(Layout.FLAT);
	}
}
