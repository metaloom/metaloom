package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.REMOTE;
import static io.metaloom.cortex.media.consistency.ConsistencyMedia.CONSISTENCY;
import static io.metaloom.cortex.media.fingerprint.FingerprintMedia.FINGERPRINT;
import static io.metaloom.cortex.media.hash.HashMedia.HASH;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.media.consistency.ConsistencyMedia;
import io.metaloom.cortex.media.fingerprint.FingerprintMedia;
import io.metaloom.cortex.media.hash.HashMedia;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.metaloom.video4j.fingerprint.Fingerprint;
import io.metaloom.video4j.fingerprint.v2.MultiSectorVideoFingerprinter;
import io.metaloom.video4j.fingerprint.v2.impl.MultiSectorVideoFingerprinterImpl;

public class FingerprintNode extends AbstractMediaNode<Void, FingerprintNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FingerprintNode.class);

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
		// return ctx.skipped("no video media").next();
		FingerprintMedia media = ctx.media(FINGERPRINT);
		HashMedia hashMedia = ctx.media(HASH);
		ConsistencyMedia consistencyMedia = ctx.media(CONSISTENCY);
		// return ctx.skipped("incomplete media").next();
		boolean isComplete = consistencyMedia.isComplete() != null && consistencyMedia.isComplete();
		boolean isVideo = media.isVideo();
		if (isVideo && !isComplete && options().isProcessIncomplete()) {
			return true;
		} else {
			return isVideo;
		}
	}

	@Override
	protected boolean isProcessed(NodeContext<LoomMedia> ctx) {
		return ctx.media(FINGERPRINT).hasFingerprint();
	}

	@Override
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
		FingerprintMedia media = ctx.media(FINGERPRINT);
		if (asset != null && asset.getFingerprint() != null) {
			media.setFingerprint(asset.getFingerprint().getFingerprintV1());
			return ctx.origin(REMOTE).next();
		} else {
			try {
				processMedia(ctx);
				return ctx.origin(COMPUTED).next();
			} catch (Exception e) {
				error(media, "Failure for " + media.path());
				if (log.isErrorEnabled()) {
					log.error("Error while processing media " + media.path(), e);
				}
				// Store failure flag
				if (media.getFingerprint() == null) {
					media.setFingerprint("NULL");
				}
				// TODO encode message
				return ctx.failure(e.getMessage()).next();
			}

		}
	}

	private void processMedia(NodeContext<LoomMedia> ctx) throws InterruptedException {
		FingerprintMedia media = ctx.media(FINGERPRINT);
		String fp = media.getFingerprint();
		boolean isNull = fp != null && fp.equals("NULL");
		boolean isCorrect = fp != null && fp.length() == 66;
		if (!options().isRetryFailed() && (isNull || isCorrect)) {
			print(ctx, "DONE", "");
		} else {

			String path = media.absolutePath();
			try (VideoFile video = Videos.open(path)) {
				Fingerprint fingerprint = hasher.hash(video);
				if (fingerprint == null) {
					print(ctx, "NULL", "(no result)");
					media.setFingerprint("NULL");
				} else {
					String hash = fingerprint.hex();
					print(ctx, "DONE", "");
					media.setFingerprint(hash);
				}
			}
		}
	}

}
