package io.metaloom.cortex.impl.loom;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.common.metrics.CortexMetrics;
import io.metaloom.cortex.pipeline.common.sync.DefaultLoomBulkSyncCollector.BulkSyncWriter;
import io.metaloom.cortex.pipeline.common.sync.DefaultLoomBulkSyncCollector.SyncEntry;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.rest.model.asset.AssetBulkUpdateEntry;
import io.metaloom.loom.rest.model.asset.AssetBulkUpdateRequest;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

/**
 * Production {@link BulkSyncWriter} that ships pipeline node results to Loom
 * via the {@code bulkUpdateAssets} REST endpoint.
 *
 * <p>Sync entries are grouped by SHA-512 (the asset identity used by Loom).
 * For each group, hash outputs (sha512 / sha256 / md5 / chunkHash) from any
 * completed node are merged into a single {@link AssetUpdateRequest}. Entries
 * whose media does not yet have a SHA-512 are skipped with a warning — Loom
 * can only identify assets by hash today.</p>
 *
 * <p>When the Loom client is not available (offline mode) the writer becomes
 * a no-op that only logs. This keeps Cortex usable without a Loom server
 * while making it obvious that no sync is occurring.</p>
 */
@Singleton
public class LoomBulkSyncWriterImpl implements BulkSyncWriter {

	private static final Logger log = LoggerFactory.getLogger(LoomBulkSyncWriterImpl.class);

	private final LoomClient loomClient;
	private final CortexMetrics metrics;

	@Inject
	public LoomBulkSyncWriterImpl(@Nullable LoomClient loomClient, CortexMetrics metrics) {
		this.loomClient = loomClient;
		this.metrics = metrics;
	}

	@Override
	public void writeBulk(List<SyncEntry> entries) throws Exception {
		if (entries == null || entries.isEmpty()) {
			return;
		}
		if (loomClient == null) {
			log.warn("No LoomClient available. Dropping {} sync entries.", entries.size());
			metrics.recordBulkSync("dropped_offline", entries.size());
			return;
		}

		// Group entries by SHA-512 (asset identity in Loom).
		Map<String, AssetBulkUpdateEntry> byHash = new LinkedHashMap<>();
		int skipped = 0;
		for (SyncEntry entry : entries) {
			LoomMedia media = entry.getMedia();
			if (media == null || !media.hasSHA512() || media.getSHA512() == null) {
				skipped++;
				continue;
			}
			String sha = media.getSHA512().toString();
			AssetBulkUpdateEntry bulk = byHash.computeIfAbsent(sha, s -> newBulkEntry(media));
			mergeOutputs(bulk.getUpdate(), entry.getResult());
		}

		if (skipped > 0) {
			metrics.recordBulkSync("skipped_no_hash", skipped);
		}

		if (byHash.isEmpty()) {
			log.warn("Bulk sync received {} entries but none had a SHA-512 identity. Nothing pushed.", entries.size());
			return;
		}

		AssetBulkUpdateRequest request = new AssetBulkUpdateRequest();
		byHash.values().forEach(request::add);
		int count = byHash.size();

		try {
			loomClient.bulkUpdateAssets(request).sync();
			metrics.recordBulkSync("synced", count);
			log.info("Bulk-synced {} assets to Loom ({} entries in batch{}).",
				count, entries.size(), skipped > 0 ? ", " + skipped + " skipped without hash" : "");
		} catch (Exception e) {
			metrics.recordBulkSync("failed", count);
			log.error("Bulk sync of {} assets to Loom failed: {}", count, e.getMessage(), e);
			throw e;
		}
	}

	private static AssetBulkUpdateEntry newBulkEntry(LoomMedia media) {
		AssetBulkUpdateEntry entry = new AssetBulkUpdateEntry();
		entry.setHashes(new HashInfo().setSHA512(media.getSHA512()));
		entry.setUpdate(new AssetUpdateRequest());
		return entry;
	}

	/**
	 * Merge output fields from a {@link NodeResult} into the given asset
	 * update. Currently maps the hash outputs; additional node outputs (like
	 * fingerprint, thumbnail, transcript) can be plumbed here as their
	 * corresponding update fields are exercised.
	 *
	 * <p>
	 * Binding is by the port's <strong>content type</strong>, not by output name: every hash node
	 * emits a port called {@code hash} and the algorithm is what distinguishes them
	 * ({@code hash/md5} vs {@code hash/sha256} …). Keying off the name is what used to let
	 * a renamed node quietly stop syncing.
	 * </p>
	 */
	private static void mergeOutputs(AssetUpdateRequest update, NodeResult result) {
		if (result == null || result.getOutputs().isEmpty()) {
			return;
		}
		HashInfo hashes = update.getHashes();
		if (hashes == null) {
			hashes = new HashInfo();
			update.setHashes(hashes);
		}

		for (PortOutput output : result.getOutputs().values()) {
			if (output == null || output.port() == null) {
				continue;
			}
			Object value = output.single();
			if (value == null) {
				continue;
			}
			String raw = value.toString();
			switch (output.port().contentType()) {
				case ContentTypeRegistry.HASH_SHA512 -> {
					if (hashes.getSHA512() == null) {
						hashes.setSHA512(SHA512.fromString(raw));
					}
				}
				case ContentTypeRegistry.HASH_SHA256 -> {
					if (hashes.getSHA256() == null) {
						hashes.setSHA256(SHA256.fromString(raw));
					}
				}
				case ContentTypeRegistry.HASH_MD5 -> {
					if (hashes.getMD5() == null) {
						hashes.setMD5(MD5.fromString(raw));
					}
				}
				case ContentTypeRegistry.HASH_CHUNK -> {
					if (hashes.getChunkHash() == null) {
						hashes.setChunkHash(ChunkHash.fromString(raw));
					}
				}
				default -> {
					// Not a hash port — nothing to merge into the asset update yet.
				}
			}
		}
	}
}
