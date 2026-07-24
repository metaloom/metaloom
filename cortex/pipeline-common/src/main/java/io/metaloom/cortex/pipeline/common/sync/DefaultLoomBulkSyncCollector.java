package io.metaloom.cortex.pipeline.common.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.pipeline.api.sync.LoomBulkSyncCollector;

/**
 * Default implementation that collects sync-eligible node results and flushes them
 * in bulk when the batch size is reached or on explicit flush.
 *
 * <p>The actual write to Loom is delegated to a {@link BulkSyncWriter} strategy,
 * allowing the collector to work with any Loom client implementation or even a
 * no-op writer for testing.</p>
 */
public class DefaultLoomBulkSyncCollector implements LoomBulkSyncCollector {

	private static final Logger log = LoggerFactory.getLogger(DefaultLoomBulkSyncCollector.class);

	private final int batchSize;
	private final BulkSyncWriter writer;
	private final List<SyncEntry> buffer = new CopyOnWriteArrayList<>();

	public DefaultLoomBulkSyncCollector(BulkSyncWriter writer, int batchSize) {
		this.writer = writer;
		this.batchSize = batchSize;
	}

	public DefaultLoomBulkSyncCollector(BulkSyncWriter writer) {
		this(writer, 100);
	}

	@Override
	public void collect(LoomMedia media, String nodeId, NodeResult result) {
		buffer.add(new SyncEntry(media, nodeId, result));
		if (buffer.size() >= batchSize) {
			flush();
		}
	}

	@Override
	public int flush() {
		if (buffer.isEmpty()) {
			return 0;
		}
		// Drain the buffer atomically
		List<SyncEntry> batch = new ArrayList<>(buffer);
		buffer.clear();
		log.info("Flushing {} sync entries to Loom", batch.size());
		try {
			writer.writeBulk(batch);
		} catch (Exception e) {
			log.error("Failed to flush sync entries to Loom: {}", e.getMessage(), e);
			// Re-add failed entries for retry on next flush
			buffer.addAll(batch);
			return 0;
		}
		return batch.size();
	}

	@Override
	public int pending() {
		return buffer.size();
	}

	/**
	 * A single entry pending synchronization.
	 */
	public static class SyncEntry {
		private final LoomMedia media;
		private final String nodeId;
		private final NodeResult result;

		public SyncEntry(LoomMedia media, String nodeId, NodeResult result) {
			this.media = media;
			this.nodeId = nodeId;
			this.result = result;
		}

		public LoomMedia getMedia() {
			return media;
		}

		public String getNodeId() {
			return nodeId;
		}

		public NodeResult getResult() {
			return result;
		}

		@Override
		public String toString() {
			return nodeId + " -> " + media.absolutePath();
		}
	}

	/**
	 * Strategy interface for actually writing a batch of results to Loom.
	 * Implementations use the Loom bulk REST API to push asset metadata in one call.
	 */
	@FunctionalInterface
	public interface BulkSyncWriter {

		/**
		 * Write a batch of sync entries to Loom.
		 *
		 * @param entries the entries to write
		 * @throws Exception if the bulk write fails
		 */
		void writeBulk(List<SyncEntry> entries) throws Exception;
	}
}
