package io.metaloom.cortex.node.loom;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractFilesystemNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.rest.model.asset.AssetBulkUpdateEntry;
import io.metaloom.loom.rest.model.asset.AssetBulkUpdateRequest;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

public class LoomNode extends AbstractFilesystemNode<LoomMedia, LoomNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(LoomNode.class);

	/**
	 * The hashes this sink writes back, bound <strong>by port</strong>.
	 *
	 * <p>
	 * These used to be {@code ctx.upstreamOutput("md5sum", "md5")} and
	 * {@code ctx.upstreamOutput("sha256sum", "sha256")} - node ids that no kind ever produces
	 * (the kinds are {@code md5} and {@code sha256}), so an editor-authored graph fed this node
	 * nothing at all and it silently wrote back SHA-512 only. Binding by port makes the
	 * mismatch unexpressible: the edge says where each hash comes from, and save-time validation
	 * refuses to connect an SHA-256 producer to the {@code md5} port.
	 * </p>
	 */
	public static final InputPort<String> IN_MD5 = InputPort.one("md5", ContentTypeRegistry.HASH_MD5, String.class);
	public static final InputPort<String> IN_SHA256 = InputPort.one("sha256", ContentTypeRegistry.HASH_SHA256, String.class);
	public static final InputPort<String> IN_SHA512 = InputPort.one("sha512", ContentTypeRegistry.HASH_SHA512, String.class);

	/**
	 * Default batch size for bulk update requests sent to Loom.
	 */
	private static final int BULK_BATCH_SIZE = 50;

	/**
	 * Accumulated update entries waiting to be flushed.
	 */
	private final List<AssetBulkUpdateEntry> pendingUpdates = new ArrayList<>();

	@Inject
	public LoomNode(@Nullable LoomClient client, CortexOptions cortexOption, LoomNodeOptions option) {
		super(client, cortexOption, option);
	}

	@Override
	public String name() {
		return "loom";
	}

	@Override
	public NodeResult process(NodeContext<LoomMedia> ctx) throws IOException {
		if (isOfflineMode()) {
			log.info("Running in offline mode. Skipping node");
			return ctx.next();
		}
		try {
			// The SHA-512 is the asset's identity, so it is taken from the media handle when no
			// producer is wired - that is the one hash every worker already has.
			String wiredSha512 = ctx.input(IN_SHA512);
			SHA512 hash = wiredSha512 != null ? SHA512.fromString(wiredSha512) : ctx.media().getSHA512();
			if (hash == null) {
				log.warn("SHA-512 is null, skipping asset");
				return ctx.next();
			}

			AssetBulkUpdateEntry entry = new AssetBulkUpdateEntry();
			HashInfo hashes = new HashInfo();
			hashes.setSHA512(hash);

			String md5 = ctx.input(IN_MD5);
			if (md5 != null) {
				hashes.setMD5(MD5.fromString(md5));
			}
			String sha256 = ctx.input(IN_SHA256);
			if (sha256 != null) {
				hashes.setSHA256(SHA256.fromString(sha256));
			}

			entry.setHashes(hashes);
			// The update payload can be extended in the future with more fields
			entry.setUpdate(new AssetUpdateRequest());

			synchronized (pendingUpdates) {
				pendingUpdates.add(entry);
				if (pendingUpdates.size() >= BULK_BATCH_SIZE) {
					flushPendingUpdates();
				}
			}
			return ctx.next();
		} catch (Exception e) {
			log.error("Error while preparing asset bulk update for Loom", e);
			return ctx.failure(e.getMessage()).next();
		}
	}

	@Override
	public void flush() {
		if (isOfflineMode()) {
			return;
		}
		synchronized (pendingUpdates) {
			flushPendingUpdates();
		}
	}

	/**
	 * Send accumulated update entries as a bulk request to Loom.
	 * Must be called from within a synchronized block on {@link #pendingUpdates}.
	 */
	private void flushPendingUpdates() {
		if (pendingUpdates.isEmpty()) {
			return;
		}

		AssetBulkUpdateRequest request = new AssetBulkUpdateRequest();
		request.setAssets(new ArrayList<>(pendingUpdates));
		pendingUpdates.clear();

		try {
			client().bulkUpdateAssets(request).sync().body();
			if (log.isDebugEnabled()) {
				log.debug("Flushed {} asset updates to Loom", request.getAssets().size());
			}
		} catch (Exception e) {
			log.error("Failed to flush bulk update ({} items) to Loom: {}", request.getAssets().size(), e.getMessage());
			// Fall back to individual updates for this batch
			for (AssetBulkUpdateEntry entry : request.getAssets()) {
				try {
					AssetUpdateRequest updateReq = entry.getUpdate() != null ? entry.getUpdate() : new AssetUpdateRequest();
					updateReq.setHashes(entry.getHashes());
					client().updateAsset(entry.getHashes().getSHA512(), updateReq).sync().body();
				} catch (Exception e2) {
					log.error("Individual fallback update also failed for {}: {}", entry.getHashes().getSHA512(), e2.getMessage());
				}
			}
		}
	}

}
