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
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.common.sync.DefaultLoomBulkSyncCollector.BulkSyncWriter;
import io.metaloom.cortex.pipeline.common.sync.DefaultLoomBulkSyncCollector.SyncEntry;
import io.metaloom.loom.client.common.LoomClient;
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

	@Inject
	public LoomBulkSyncWriterImpl(@Nullable LoomClient loomClient) {
		this.loomClient = loomClient;
	}

	@Override
	public void writeBulk(List<SyncEntry> entries) throws Exception {
		if (entries == null || entries.isEmpty()) {
			return;
		}
		if (loomClient == null) {
			log.warn("No LoomClient available. Dropping {} sync entries.", entries.size());
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

		if (byHash.isEmpty()) {
			log.warn("Bulk sync received {} entries but none had a SHA-512 identity. Nothing pushed.", entries.size());
			return;
		}

		AssetBulkUpdateRequest request = new AssetBulkUpdateRequest();
		byHash.values().forEach(request::add);
		int count = byHash.size();

		try {
			loomClient.bulkUpdateAssets(request).sync();
			log.info("Bulk-synced {} assets to Loom ({} entries in batch{}).",
				count, entries.size(), skipped > 0 ? ", " + skipped + " skipped without hash" : "");
		} catch (Exception e) {
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
	 */
	private static void mergeOutputs(AssetUpdateRequest update, NodeResult result) {
		if (result == null || result.getOutput() == null) {
			return;
		}
		HashInfo hashes = update.getHashes();
		if (hashes == null) {
			hashes = new HashInfo();
			update.setHashes(hashes);
		}

		Object sha512 = result.getOutput().get("sha512");
		if (sha512 != null && hashes.getSHA512() == null) {
			hashes.setSHA512(SHA512.fromString(sha512.toString()));
		}
		Object sha256 = result.getOutput().get("sha256");
		if (sha256 != null && hashes.getSHA256() == null) {
			hashes.setSHA256(SHA256.fromString(sha256.toString()));
		}
		Object md5 = result.getOutput().get("md5");
		if (md5 != null && hashes.getMD5() == null) {
			hashes.setMD5(MD5.fromString(md5.toString()));
		}
		Object chunk = result.getOutput().get("chunkHash");
		if (chunk != null && hashes.getChunkHash() == null) {
			hashes.setChunkHash(ChunkHash.fromString(chunk.toString()));
		}
	}
}
