package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.metaloom.video4j.fingerprint.Fingerprint;
import io.metaloom.video4j.fingerprint.v2.MultiSectorVideoFingerprinter;
import io.metaloom.video4j.fingerprint.v2.impl.MultiSectorVideoFingerprinterImpl;

public class FingerprintNode extends AbstractMediaNode<FingerprintNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FingerprintNode.class);

	public static final NodeOutputKey<String> OUTPUT_FINGERPRINT = NodeOutputKey.of("fingerprint", String.class);

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

		// Skip if fingerprint was already computed during this worker's lifetime
		if (fingerprintCache.containsKey(media.absolutePath())) {
			return false;
		}

		// Check consistency from upstream results
		Boolean isComplete = ctx.upstreamOutput("consistency", "is_complete");
		if (isComplete != null && !isComplete && !options().isProcessIncomplete()) {
			return false;
		}
		return true;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		LoomMedia media = ctx.media();
		if (asset != null && asset.getFingerprint() != null) {
			String fp = asset.getFingerprint().getFingerprintV1();
			ctx.output(OUTPUT_FINGERPRINT, fp);
			return ctx.origin(REMOTE).next();
		} else {
			try {
				String hash = computeFingerprint(media);
				String value = hash != null ? hash : "NULL";
				ctx.output(OUTPUT_FINGERPRINT, value);
				fingerprintCache.put(media.absolutePath(), value);
				print(ctx, hash != null ? "DONE" : "NULL", "");
				return ctx.origin(COMPUTED).next();
			} catch (Exception e) {
				error(media, "Failure for " + media.path());
				if (log.isErrorEnabled()) {
					log.error("Error while processing media " + media.path(), e);
				}
				ctx.output(OUTPUT_FINGERPRINT, "NULL");
				return ctx.failure(e.getMessage()).next();
			}
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
