package io.metaloom.cortex.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import dagger.Lazy;
import io.metaloom.cortex.api.node.CortexNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.impl.boot.CortexBootstrapInitializer;
import io.metaloom.cortex.pipeline.common.sync.DefaultLoomBulkSyncCollector;
import io.metaloom.cortex.pipeline.common.sync.DefaultLoomBulkSyncCollector.SyncEntry;

/**
 * Guards the shutdown-flush behaviour: results that were buffered in the bulk-sync collector but not yet flushed (they
 * did not reach the batch size) must be pushed to Loom when Cortex shuts down, rather than being silently dropped. See
 * spec/features/metastorage/METASTORAGE_FEEDBACK.md item #1.
 */
public class CortexImplShutdownFlushTest {

	@Test
	void testGracefulShutdownFlushesBufferedEntries() throws Exception {
		List<SyncEntry> written = new CopyOnWriteArrayList<>();
		// Batch size is well above the single buffered entry, so nothing auto-flushes before shutdown.
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(written::addAll, 1000);
		collector.collect(null, "sha512", null);
		assertEquals(1, collector.pending(), "Precondition: one entry buffered, not yet flushed");

		CortexImpl cortex = newCortex(collector);
		cortex.run(false);
		assertEquals(1, collector.pending(), "Startup must not flush the buffer");

		cortex.shutdown();

		assertEquals(0, collector.pending(), "Shutdown must drain the buffer");
		assertEquals(1, written.size(), "The buffered entry must have been written to Loom during shutdown");
	}

	@Test
	void testShutdownWithEmptyBufferIsANoOp() throws Exception {
		List<SyncEntry> written = new CopyOnWriteArrayList<>();
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(written::addAll, 1000);

		CortexImpl cortex = newCortex(collector);
		cortex.run(false);
		cortex.shutdown();

		assertEquals(0, written.size(), "An empty buffer must not trigger any write on shutdown");
	}

	private CortexImpl newCortex(DefaultLoomBulkSyncCollector collector) {
		Lazy<Set<CortexNode<?, ?>>> nodes = Collections::emptySet;
		return new CortexImpl(new CortexOptions(), nodes, noopBoot(), collector);
	}

	/**
	 * A bootstrap initializer whose start/stop are no-ops, so the test exercises only the sync-flush lifecycle without
	 * standing up the monitoring service or the Loom control channel.
	 */
	private static CortexBootstrapInitializer noopBoot() {
		return new CortexBootstrapInitializer(null, null, null) {
			@Override
			public void init(int port) {
				// no-op
			}

			@Override
			public void deinit() {
				// no-op
			}

			@Override
			public Integer actualMonitoringPort() {
				return 0;
			}
		};
	}
}
