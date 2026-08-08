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
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupListResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupMemberModel;
import io.metaloom.loom.rest.model.dedup.DedupGroupResponse;
import io.metaloom.utils.fs.FileUtils;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * Apply node: acts on human-CONFIRMED dedup groups. For the current asset, if it is a DUP member of a confirmed group whose KEEP still passes the
 * live safeguards, it moves the duplicate file into the configured dups folder. It never touches PENDING or REJECTED groups.
 *
 * <p>
 * Safeguards re-verified against the live filesystem before any move (spec §4/§5): the KEEP exists on disk, is complete, is at least as large as the
 * duplicate, is not itself inside the dups folder, and still hashes to the value Loom recorded. Idempotent: an already-moved duplicate is skipped.
 * </p>
 */
@NodeSpec(nodeId = "fingerprint-dedup-apply", name = "Fingerprint Deduplication (Apply)", icon = "content_copy", category = NodeCategory.OUTPUT,
	description = "Move confirmed near-duplicate files. Acts only on dedup groups a reviewer has confirmed in Loom.",
	defaultMode = io.metaloom.loom.nodes.spec.NodeMode.SEQUENTIAL,
	parameters = {
		// The dedup descriptors have only ever advertised `enabled` of the three common knobs.
		@ParamOverride(key = "processIncomplete", hidden = true),
		@ParamOverride(key = "retryFailed", hidden = true),
		// DedupNodeOptions is shared with hash-dedup, whose @ParamDoc says "duplicate files"; this node
		// only ever moves files a reviewer confirmed, and its descriptor says so.
		@ParamOverride(key = "dupFolder", label = "Duplicates Folder", description = "Target folder for confirmed duplicate files")
	})
public class FingerprintDedupApplyNode extends AbstractMediaNode<DedupNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FingerprintDedupApplyNode.class);

	/**
	 * The hash that identifies the asset whose confirmed dedup groups are applied.
	 *
	 * <p>
	 * Declared so the node's own contract states the input its descriptor has always advertised. The
	 * node looks the asset up by the media handle's SHA-512 rather than reading this port, so wiring it
	 * is what orders a hash node before this one, not what feeds it.
	 * </p>
	 */
	@PortDoc(label = "Hash", description = "Any content hash; identifies the asset whose confirmed dedup groups are applied")
	public static final InputPort<String> IN_HASH = InputPort.one("hash", ContentTypeRegistry.HASH_ANY, String.class);

	private static final String STATUS_CONFIRMED = DedupGroupResponse.STATUS_CONFIRMED;

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

	/**
	 * The KEEP must exist on disk, be complete, be at least as large as the duplicate, not itself live in the dups folder, and still hold the content
	 * that was fingerprinted.
	 */
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
		if (!keepContentMatches(keep, keepFile)) {
			return false; // the keep is no longer the file the reviewer decided about
		}
		return true;
	}

	/**
	 * The KEEP's bytes must still be the ones Loom recorded when the group was proposed.
	 *
	 * <p>
	 * Existence, size and completeness say nothing about content: a file replaced in place between discovery and apply passes all three while no longer
	 * being the duplicate's counterpart. Deleting the other copy at that point loses the only remaining original.
	 * </p>
	 *
	 * <p>
	 * <b>The stored hash is trusted.</b> {@code LoomMedia.getSHA512()} reads the {@code loom_sha512} xattr (and the legacy {@code sha512sum} key) and
	 * only digests the file when neither is present - so this is a cheap attribute read for anything a hash node has already seen, and writes the
	 * attribute back for next time when it is not. An asset with no recorded hash is passed through rather than re-digested: there would be nothing to
	 * compare the result against.
	 * </p>
	 */
	private boolean keepContentMatches(AssetResponse keep, File keepFile) {
		SHA512 recorded = keep.getHashes() == null ? null : keep.getHashes().getSHA512();
		if (recorded == null) {
			return true;
		}
		SHA512 actual;
		try {
			actual = hashOf(keepFile);
		} catch (Exception e) {
			log.warn("Could not determine the hash of keep file {} - refusing to move its duplicate: {}", keepFile, e.getMessage());
			return false;
		}
		if (actual == null || !recorded.toString().equals(actual.toString())) {
			log.warn("Keep file {} no longer matches the hash recorded at discovery time (recorded {}, found {}) - refusing to move its duplicate.",
				keepFile, recorded, actual);
			return false;
		}
		return true;
	}

	/**
	 * The KEEP's current hash, from its xattr when one is stored and by digesting the file when not.
	 *
	 * <p>
	 * {@link LoomMediaImpl} owns that precedence (including the legacy {@code sha512sum} key) and writes the attribute back once computed. It raises on
	 * a filesystem with no user-xattr support at all, which says nothing about the file's content - fall back to a plain digest there rather than
	 * blocking every move on the storage backend.
	 * </p>
	 */
	private static SHA512 hashOf(File file) {
		try {
			return new LoomMediaImpl(file.toPath()).getSHA512();
		} catch (Exception e) {
			log.debug("Extended attributes unavailable for {} - digesting directly: {}", file, e.getMessage());
			return HashUtils.computeSHA512(file);
		}
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
