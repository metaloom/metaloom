package io.metaloom.loom.rest.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.db.jooq.search.SearchEmbeddingService;
import io.vertx.core.Vertx;

/**
 * The periodic pass must not overlap itself.
 *
 * <p>
 * {@code Vertx.setPeriodic} fires on a clock rather than on completion, so a pass slower than the interval is joined by the next one rather than
 * replacing it. Against a CPU embedding host that is not a slow drain, it is a pile-up: on metaloom.sky a 5s interval built up two dozen concurrent
 * 32-document requests, each queued behind the others inside the host, until every one of them breached the client timeout in the same millisecond
 * and the backlog made no progress at all. Abandoning a queued request does not cancel the work the host is committed to, so the overload feeds
 * itself.
 * </p>
 */
public class SearchEmbeddingDrainerTest {

	private Vertx vertx;

	@AfterEach
	public void closeVertx() {
		if (vertx != null) {
			vertx.close();
		}
	}

	private SearchOptions options(int intervalMs) {
		return new SearchOptions().setSemanticEnabled(true).setEmbedSyncIntervalMs(intervalMs).setEmbedBatchSize(8);
	}

	@Test
	public void shouldNotStartASecondPassWhileTheFirstIsStillRunning() throws Exception {
		vertx = Vertx.vertx();
		SearchEmbeddingService service = Mockito.mock(SearchEmbeddingService.class);
		Mockito.when(service.isReady()).thenReturn(true);

		AtomicInteger concurrent = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		AtomicInteger passes = new AtomicInteger();
		CountDownLatch enough = new CountDownLatch(3);

		// Each pass takes far longer than the interval — exactly the shape that caused the pile-up.
		Mockito.when(service.embedStale(Mockito.anyInt())).thenAnswer(invocation -> {
			int now = concurrent.incrementAndGet();
			peak.accumulateAndGet(now, Math::max);
			try {
				Thread.sleep(120);
			} finally {
				concurrent.decrementAndGet();
				passes.incrementAndGet();
				enough.countDown();
			}
			return 0;
		});

		SearchEmbeddingDrainer drainer = new SearchEmbeddingDrainer(vertx, service, options(10));
		drainer.start();
		assertTrue(enough.await(10, TimeUnit.SECONDS), "the pass should have run several times by now");
		drainer.stop();

		assertEquals(1, peak.get(),
			"A tick that lands while a pass is running must be skipped, not queued alongside it");
		assertTrue(passes.get() >= 3, "and the passes that are not skipped must still run");
	}

	@Test
	public void shouldNotStartWhenSemanticSearchIsUnconfigured() {
		vertx = Vertx.vertx();
		SearchEmbeddingService service = Mockito.mock(SearchEmbeddingService.class);
		Mockito.when(service.isReady()).thenReturn(false);

		new SearchEmbeddingDrainer(vertx, service, options(10)).start();

		// Not merely "does nothing useful": the timer must never be armed, because the boot probe is the
		// only readiness check and a running timer would hammer a host that is known to be absent.
		Mockito.verify(service, Mockito.never()).embedStale(Mockito.anyInt());
	}
}
