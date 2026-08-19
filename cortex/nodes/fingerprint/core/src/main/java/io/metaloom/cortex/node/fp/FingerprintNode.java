package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompCreateRequest;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.metaloom.video4j.fingerprint.Fingerprint;
import io.metaloom.video4j.fingerprint.v2.MultiSectorVideoFingerprinter;
import io.metaloom.video4j.fingerprint.v2.impl.MultiSectorVideoFingerprinterImpl;

@NodeSpec(nodeId = "fingerprint", name = "Fingerprint", icon = "grain", category = NodeCategory.ANALYSIS,
	description = "Compute a perceptual fingerprint of the media for deduplication or similarity search.",
	defaultConcurrency = 2)
public class FingerprintNode extends AbstractMediaNode<FingerprintNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FingerprintNode.class);

	@PortDoc(label = "Video", description = "The video whose frames are sampled into a perceptual hash")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_VIDEO, LoomMedia.class);

	/**
	 * Whether the file is whole. Optional and declared rather than looked up: this used to be
	 * {@code ctx.upstreamOutput("consistency", "is_complete")}, which silently produced nothing
	 * the moment a pipeline author named the node anything other than {@code consistency}.
	 */
	@PortDoc(label = "Is Complete", required = false,
		description = "Whether the file is whole; an incomplete one is skipped unless processIncomplete is set")
	public static final InputPort<Boolean> IN_IS_COMPLETE = InputPort.one("is_complete", ContentTypeRegistry.SCALAR_BOOLEAN, Boolean.class);

	@PortDoc(label = "Fingerprint",
		description = "Perceptual fingerprint that survives re-encoding, so near-duplicates still compare equal")
	public static final OutputPort<String> OUT_FINGERPRINT = OutputPort.one("fingerprint", ContentTypeRegistry.HASH_FINGERPRINT, String.class);

	/** Identifier of the fingerprint algorithm this node produces; part of the persisted component's natural key. */
	private static final String ALGORITHM = "metaloom-multisector-v1";

	/**
	 * Upper bound for the in-memory skip cache. Fingerprinting a video is expensive, so we remember which media were already
	 * fingerprinted during this worker's lifetime to avoid recomputation. The cache is intentionally non-durable — the durable
	 * copy lives in Loom, which is checked first in {@link #compute(NodeContext, AssetResponse)}.
	 */
	private static final int FINGERPRINT_CACHE_SIZE = 100_000;

	private MultiSectorVideoFingerprinter hasher = new MultiSectorVideoFingerprinterImpl();

	/**
	 * Lightweight in-memory LRU cache of media paths that already have a fingerprint, keyed by absolute path.
	 */
	private final Map<String, String> fingerprintCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
			return size() > FINGERPRINT_CACHE_SIZE;
		}
	});

	static {
		Video4j.init();
	}

	@Inject
	public FingerprintNode(@Nullable LoomClient client, CortexOptions cortexOption, FingerprintNodeOptions options) {
		super(client, cortexOption, options);
	}

	/**
	 * Visible for testing: seed the in-memory skip cache so the node treats the given media as already fingerprinted.
	 *
	 * @param media
	 * @param fingerprint
	 */
	void markFingerprinted(LoomMedia media, String fingerprint) {
		fingerprintCache.put(media.absolutePath(), fingerprint);
	}

	@Override
	public String name() {
		return "fingerprint";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		if (!media.isVideo()) {
			return false;
		}

		// Check consistency from the declared input port
		Boolean isComplete = ctx.input(IN_IS_COMPLETE);
		if (isComplete != null && !isComplete && !options().isProcessIncomplete()) {
			return false;
		}
		return true;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		LoomMedia media = ctx.media();

		// Re-emit a fingerprint this worker already computed instead of recomputing it. The check
		// has to live here rather than in isProcessable(): skipping there reports SKIPPED and emits
		// nothing on OUT_FINGERPRINT, which starves every node bound to that port. A cache hit is
		// still a result, so it is reported as SUCCESS with a LOCAL origin.
		String cached = fingerprintCache.get(media.absolutePath());
		if (cached != null) {
			ctx.output(OUT_FINGERPRINT, cached);
			return ctx.origin(LOCAL).next();
		}

		if (asset != null && asset.getFingerprint() != null) {
			String fp = asset.getFingerprint().getFingerprintV1();
			ctx.output(OUT_FINGERPRINT, fp);
			return ctx.origin(REMOTE).next();
		} else {
			try {
				String hash = computeFingerprint(media);
				String value = hash != null ? hash : "NULL";
				ctx.output(OUT_FINGERPRINT, value);
				fingerprintCache.put(media.absolutePath(), value);
				if (hash != null) {
					persist(ctx, asset, hash);
				}
				print(ctx, hash != null ? "DONE" : "NULL", "");
				return ctx.origin(COMPUTED).next();
			} catch (Exception e) {
				error(media, "Failure for " + media.path());
				if (log.isErrorEnabled()) {
					log.error("Error while processing media " + media.path(), e);
				}
				ctx.output(OUT_FINGERPRINT, "NULL");
				return ctx.failure(e.getMessage()).abort();
			}
		}
	}

	/**
	 * Persist the computed whole-video fingerprint as a whole-asset (window 0) {@code asset_fingerprint_comp} row and record a ledger entry. Best-effort and a no-op
	 * when the asset is not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, String fingerprint) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			FingerprintCompCreateRequest request = new FingerprintCompCreateRequest();
			request.setNodeKind(name());
			request.setAlgorithm(ALGORITHM);
			request.setWindowIndex(0);
			request.setFingerprint(fingerprint);
			UUID compUuid = client().createAssetFingerprintComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, ALGORITHM, resultRef("asset_fingerprint_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist fingerprint for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), ALGORITHM, null);
		}
	}

	private String computeFingerprint(LoomMedia media) throws InterruptedException {
		String path = media.absolutePath();
		try (VideoFile video = Videos.open(path)) {
			Fingerprint fingerprint = hasher.hash(video);
			return fingerprint != null ? fingerprint.hex() : null;
		}
	}

}
