package io.metaloom.cortex.node.dedup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeMode;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupCreateRequest;
import io.metaloom.loom.rest.model.dedup.DedupGroupMemberModel;
import io.metaloom.loom.rest.model.dedup.DedupGroupResponse;
import io.metaloom.loom.rest.model.similarity.SimilarAssetListResponse;
import io.metaloom.loom.rest.model.similarity.SimilarAssetResponse;

/**
 * Discovery node: finds near-duplicate videos via the fingerprint similarity index and reports candidate groups to Loom for human review. It never
 * moves or alters a file - that is the apply node's job once a group is confirmed.
 *
 * <p>
 * Mirrors the safeguards from {@code xdb-clean/FPDEDUP_PROCESS.md} that map cleanly: self-exclusion, KEEP = largest complete asset, abort when a DUP
 * is larger than the KEEP, and never propose discarding an incomplete/more-complete file. See spec/features/pipeline-nodes/NODE_DEDUP_PLAN.md §3.
 * </p>
 */
@NodeSpec(nodeId = "fingerprint-dedup", name = "Fingerprint Deduplication", icon = "content_copy",
	category = NodeCategory.OUTPUT,
	description = "Detect and move near-duplicate files based on perceptual fingerprint similarity.",
	defaultMode = NodeMode.SEQUENTIAL,
	// The dedup descriptors have only ever advertised `enabled` of the three common knobs.
	parameters = {
		@ParamOverride(key = "processIncomplete", hidden = true),
		@ParamOverride(key = "retryFailed", hidden = true) })
public class FingerprintDedupNode extends AbstractMediaNode<FingerprintDedupDiscoverOptions> {

	/**
	 * The perceptual fingerprint to search on.
	 *
	 * <p>
	 * Declared here for the same reason as on the other dedup nodes: the contract is derived from these
	 * constants, and the descriptor has always advertised this port.
	 * </p>
	 */
	@PortDoc(label = "Fingerprint",
		description = "A perceptual fingerprint; files within the similarity threshold count as near-duplicates")
	public static final InputPort<String> IN_FINGERPRINT = InputPort.one("fingerprint",
		ContentTypeRegistry.HASH_FINGERPRINT, String.class);

	public static final Logger log = LoggerFactory.getLogger(FingerprintDedupNode.class);

	@Inject
	public FingerprintDedupNode(@Nullable LoomClient client, CortexOptions cortexOptions, FingerprintDedupDiscoverOptions options) {
		super(client, cortexOptions, options);
	}

	@Override
	public String name() {
		return "fingerprint-dedup";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().isVideo();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		if (isOfflineMode() || asset == null || client() == null) {
			return ctx.skipped("offline or asset unknown to Loom").next();
		}
		if (asset.getFingerprint() == null || asset.getFingerprint().getFingerprintV1() == null) {
			return ctx.skipped("no fingerprint").next();
		}

		String algorithm = options().getAlgorithm();
		SimilarAssetListResponse similar;
		try {
			similar = client().listSimilarAssets(asset.getUuid(), algorithm, options().getTopK(), options().getScoreThreshold()).sync().body();
		} catch (Exception e) {
			log.warn("Similarity query failed for asset {}: {}", asset.getUuid(), e.getMessage());
			return ctx.failure("similarity query failed: " + e.getMessage()).next();
		}
		if (similar == null || similar.getData() == null || similar.getData().isEmpty()) {
			return ctx.skipped("no similar assets").next();
		}

		// Build the candidate set: the query asset plus each distinct near-duplicate hit (self excluded).
		List<Candidate> candidates = new ArrayList<>();
		candidates.add(candidateOf(asset, 1.0f));
		for (SimilarAssetResponse hit : similar.getData()) {
			if (hit.getAssetUuid() == null || hit.getAssetUuid().equals(asset.getUuid().toString())) {
				continue; // self-exclusion safeguard
			}
			AssetResponse hitAsset;
			try {
				hitAsset = client().loadAsset(UUID.fromString(hit.getAssetUuid())).sync().body();
			} catch (Exception e) {
				continue;
			}
			if (hitAsset != null) {
				candidates.add(candidateOf(hitAsset, hit.getScore()));
			}
		}
		if (candidates.size() < 2) {
			return ctx.skipped("no duplicate candidates").next();
		}

		// KEEP = largest COMPLETE asset. If none is complete, only proceed when allowPartial is set.
		Optional<Candidate> keepOpt = candidates.stream()
			.filter(Candidate::isComplete)
			.max(Comparator.comparingLong(c -> c.size));
		if (keepOpt.isEmpty()) {
			if (!options().isAllowPartial()) {
				return ctx.skipped("no complete keep candidate").next();
			}
			keepOpt = candidates.stream().max(Comparator.comparingLong(c -> c.size));
		}
		Candidate keep = keepOpt.get();

		List<Candidate> dups = new ArrayList<>();
		for (Candidate c : candidates) {
			if (c.assetUuid.equals(keep.assetUuid)) {
				continue;
			}
			// Never propose discarding a file larger than the keep.
			if (options().isAbortOnLargerDup() && c.size > keep.size) {
				return ctx.skipped("a duplicate is larger than the keep - aborting group").next();
			}
			dups.add(c);
		}
		if (dups.isEmpty()) {
			return ctx.skipped("no duplicate members after safeguards").next();
		}

		// Emit the candidate group to Loom (PENDING). No file is moved.
		DedupGroupCreateRequest request = new DedupGroupCreateRequest()
			.setAlgorithm(algorithm)
			.setKeepAssetUuid(keep.assetUuid.toString())
			.setScore(dups.stream().map(d -> d.score).filter(s -> s != null).min(Comparator.naturalOrder()).orElse(null));
		List<DedupGroupMemberModel> members = new ArrayList<>();
		members.add(member(keep, DedupGroupMemberModel.ROLE_KEEP));
		for (Candidate d : dups) {
			members.add(member(d, DedupGroupMemberModel.ROLE_DUP));
		}
		request.setMembers(members);

		try {
			DedupGroupResponse group = client().createDedupGroup(request).sync().body();
			UUID groupUuid = group != null && group.getUuid() != null ? UUID.fromString(group.getUuid()) : null;
			print(ctx, "CANDIDATE", dups.size() + " duplicate(s) of " + keep.assetUuid);
			recordNodeResult(asset, ctx, ResultState.SUCCESS, dups.size() + " duplicate candidate(s)", algorithm,
				groupUuid == null ? null : resultRef("dedup_group", groupUuid));
			return ctx.next();
		} catch (Exception e) {
			log.warn("Failed to report dedup group for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), algorithm, null);
			return ctx.failure("failed to report dedup group").next();
		}
	}

	private static DedupGroupMemberModel member(Candidate c, String role) {
		return new DedupGroupMemberModel()
			.setAssetUuid(c.assetUuid.toString())
			.setRole(role)
			.setScore(c.score)
			.setSize(c.size)
			.setZeroChunkCount(c.zeroChunkCount);
	}

	private static Candidate candidateOf(AssetResponse asset, Float score) {
		long size = asset.getFile() != null ? asset.getFile().getSize() : 0L;
		Long zero = asset.getConsistency() != null ? asset.getConsistency().getZeroChunkCount() : null;
		return new Candidate(asset.getUuid(), size, zero, score);
	}

	/** A duplicate-set candidate with the fields the safeguards and the review record need. */
	private static final class Candidate {
		final UUID assetUuid;
		final long size;
		final Long zeroChunkCount;
		final Float score;

		Candidate(UUID assetUuid, long size, Long zeroChunkCount, Float score) {
			this.assetUuid = assetUuid;
			this.size = size;
			this.zeroChunkCount = zeroChunkCount;
			this.score = score;
		}

		boolean isComplete() {
			return zeroChunkCount == null || zeroChunkCount == 0L;
		}
	}
}
