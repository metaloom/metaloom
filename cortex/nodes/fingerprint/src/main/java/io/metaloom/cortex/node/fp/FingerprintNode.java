package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.payload.EmbeddingPayload;
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

public class FingerprintNode extends AbstractMediaNode<EmbeddingPayload, FingerprintNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FingerprintNode.class);

	public static final String OUTPUT_FINGERPRINT = "fingerprint";

	private MultiSectorVideoFingerprinter hasher = new MultiSectorVideoFingerprinterImpl();

	static {
		Video4j.init();
	}

	@Inject
	public FingerprintNode(@Nullable LoomClient client, CortexOptions cortexOption, FingerprintNodeOptions options) {
		super(client, cortexOption, options);
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

		// Check consistency from upstream results
		Boolean isComplete = ctx.upstreamOutput("consistency", "is_complete");
		if (isComplete != null && !isComplete && !options().isProcessIncomplete()) {
			return false;
		}
		return true;
	}

	@Override
	protected NodeResult<EmbeddingPayload> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		LoomMedia media = ctx.media();
		if (asset != null && asset.getFingerprint() != null) {
			String fp = asset.getFingerprint().getFingerprintV1();
			ctx.output(OUTPUT_FINGERPRINT, fp);
			return ctx.origin(REMOTE).next(fp != null ? EmbeddingPayload.ofHex(fp) : null);
		} else {
			try {
				String hash = computeFingerprint(media);
				ctx.output(OUTPUT_FINGERPRINT, hash != null ? hash : "NULL");
				print(ctx, hash != null ? "DONE" : "NULL", "");
				return ctx.origin(COMPUTED).next(hash != null ? EmbeddingPayload.ofHex(hash) : null);
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
