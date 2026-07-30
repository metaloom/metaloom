package io.metaloom.cortex.node.dedup;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupListResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupMemberModel;
import io.metaloom.loom.rest.model.dedup.DedupGroupResponse;
import io.metaloom.utils.fs.FileUtils;

/**
 * Apply node: acts on human-CONFIRMED dedup groups. For the current asset, if it is a DUP member of a confirmed group whose KEEP still passes the
 * live safeguards, it moves the duplicate file into the configured dups folder. It never touches PENDING or REJECTED groups.
 *
 * <p>
 * Safeguards re-verified against the live filesystem before any move (spec §4/§5): the KEEP exists on disk, is complete, is at least as large as the
 * duplicate, and is not itself inside the dups folder. Idempotent: an already-moved duplicate is skipped.
 * </p>
 */
public class FingerprintDedupApplyNode extends AbstractMediaNode<DedupNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FingerprintDedupApplyNode.class);

	private static final String STATUS_CONFIRMED = "CONFIRMED";

	@Inject
	public FingerprintDedupApplyNode(@Nullable LoomClient client, CortexOptions cortexOptions, DedupNodeOptions options) {
		super(client, cortexOptions, options);
	}

	@Override
	public String name() {
		return "fingerprint-dedup-apply";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().getSHA512() != null;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		if (isOfflineMode() || asset == null || client() == null) {
			return ctx.skipped("offline or asset unknown to Loom").next();
		}

		DedupGroupListResponse groups;
		try {
			groups = client().listAssetDedupGroups(asset.getUuid()).sync().body();
		} catch (Exception e) {
			log.warn("Failed to load dedup groups for asset {}: {}", asset.getUuid(), e.getMessage());
			return ctx.failure("failed to load dedup groups").next();
		}
		if (groups == null || groups.getData() == null || groups.getData().isEmpty()) {
			return ctx.skipped("no dedup groups").next();
		}

		LoomMedia media = ctx.media();
		Path dupFolder = options().getDupFolder();

		for (DedupGroupResponse group : groups.getData()) {
			if (!STATUS_CONFIRMED.equals(group.getStatus())) {
				continue; // only ever act on confirmed decisions
			}
			if (!isDupMember(group, asset.getUuid())) {
				continue;
			}
			String keepUuid = group.getKeepAssetUuid();
			if (keepUuid == null) {
				continue;
			}
			AssetResponse keep;
			try {
				keep = client().loadAsset(UUID.fromString(keepUuid)).sync().body();
			} catch (Exception e) {
				continue;
			}
			if (!keepPassesSafeguards(keep, media, dupFolder)) {
				continue;
			}

			// Idempotency: if this duplicate is already inside the dups folder, there is nothing to do.
			if (isInFolder(media.file(), dupFolder)) {
				return ctx.skipped("already moved to dups folder").next();
			}

			String keepPath = keep.getFile().getFilename();
			ensureFolder(dupFolder);
			NodeResult moved = moveMedia(ctx, dupFolder, "fpdup of " + keepPath);
			recordNodeResult(asset, ctx, ResultState.SUCCESS, "fpdup of " + keepPath, null, null);
			return moved;
		}

		return ctx.skipped("no confirmed dedup action for this asset").next();
	}

	private boolean isDupMember(DedupGroupResponse group, UUID assetUuid) {
		if (group.getMembers() == null) {
			return false;
		}
		String uuid = assetUuid.toString();
		return group.getMembers().stream()
			.anyMatch(m -> uuid.equals(m.getAssetUuid()) && DedupGroupMemberModel.ROLE_DUP.equals(m.getRole()));
	}

	/** The KEEP must exist on disk, be complete, be at least as large as the duplicate, and not itself live in the dups folder. */
	private boolean keepPassesSafeguards(AssetResponse keep, LoomMedia dupMedia, Path dupFolder) throws IOException {
		if (keep == null || keep.getFile() == null || keep.getFile().getFilename() == null) {
			return false;
		}
		File keepFile = new File(keep.getFile().getFilename());
		if (!keepFile.exists()) {
			return false;
		}
		Long keepZero = keep.getConsistency() != null ? keep.getConsistency().getZeroChunkCount() : null;
		if (keepZero != null && keepZero != 0L) {
			return false; // keep incomplete - never discard the more-complete file
		}
		if (keep.getFile().getSize() < dupMedia.size()) {
			return false; // keep smaller than the duplicate
		}
		if (isInFolder(keepFile, dupFolder)) {
			return false; // keep is itself a trashed/duplicate file
		}
		return true;
	}

	private static boolean isInFolder(File file, Path folder) {
		if (file == null || folder == null) {
			return false;
		}
		String f = file.getAbsoluteFile().toPath().normalize().toString();
		String dir = folder.toAbsolutePath().normalize().toString();
		return f.startsWith(dir);
	}

	private static void ensureFolder(Path folder) {
		if (!Files.exists(folder)) {
			try {
				Files.createDirectories(folder);
			} catch (FileAlreadyExistsException e) {
				// ignored
			} catch (Exception e) {
				throw new RuntimeException("Could not create dups target dir {" + folder.toAbsolutePath() + "}");
			}
		}
	}

	/** Move the current media into the target folder (respecting dry-run), mirroring {@code HashDedupNode.moveMedia}. */
	private NodeResult moveMedia(NodeContext<LoomMedia> ctx, Path targetFolder, String msg) {
		LoomMedia media = ctx.media();
		try {
			File targetFile = FileUtils.autoRotate(media.file(), targetFolder.toFile());
			print(ctx, "MOVING", "[" + media.path() + "] to [" + targetFolder.toAbsolutePath() + "] " + msg);
			if (!isDryrun()) {
				FileUtils.moveFile(media.file(), targetFile);
			}
			return ctx.next();
		} catch (IOException e) {
			print(ctx, "FAILED", "(Error while moving, " + msg + ")");
			return ctx.failure("error while moving").next();
		}
	}
}
