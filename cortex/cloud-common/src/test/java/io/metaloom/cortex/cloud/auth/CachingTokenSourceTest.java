package io.metaloom.cortex.cloud.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

public class CachingTokenSourceTest {

	/** A clock the test moves by hand, so expiry is exercised without sleeping. */
	private static class MovableClock extends Clock {
		private long millis = 1_000_000;

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(millis);
		}

		@Override
		public long millis() {
			return millis;
		}

		void advance(long delta) {
			millis += delta;
		}
	}

	private static class CountingSource extends AbstractCachingTokenSource {
		final AtomicInteger fetches = new AtomicInteger();
		Long expiresIn = 3600L;
		CountDownLatch gate;

		CountingSource(Clock clock) {
			super(clock);
		}

		@Override
		public String accountId() {
			return "test";
		}

		@Override
		protected JsonObject fetch() throws IOException {
			if (gate != null) {
				try {
					gate.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			JsonObject response = new JsonObject().put("access_token", "token-" + fetches.incrementAndGet());
			if (expiresIn != null) {
				response.put("expires_in", expiresIn);
			}
			return response;
		}
	}

	@Test
	public void testTokenIsReusedWhileValid() throws IOException {
		MovableClock clock = new MovableClock();
		CountingSource source = new CountingSource(clock);

		assertThat(source.accessToken()).isEqualTo("token-1");
		clock.advance(60_000);
		assertThat(source.accessToken()).isEqualTo("token-1");
		assertThat(source.fetches).hasValue(1);
	}

	@Test
	public void testRefreshesBeforeTheStatedExpiry() throws IOException {
		MovableClock clock = new MovableClock();
		CountingSource source = new CountingSource(clock);
		source.accessToken();

		// Still inside the skew margin: a token handed out in its last minute is a request that
		// fails in flight.
		clock.advance(3600_000 - AbstractCachingTokenSource.SKEW_MS + 1);
		assertThat(source.accessToken()).isEqualTo("token-2");
	}

	@Test
	public void testStillCachedJustBeforeTheSkewWindow() throws IOException {
		MovableClock clock = new MovableClock();
		CountingSource source = new CountingSource(clock);
		source.accessToken();

		clock.advance(3600_000 - AbstractCachingTokenSource.SKEW_MS - 1000);
		assertThat(source.accessToken()).isEqualTo("token-1");
	}

	@Test
	public void testMissingExpiresInFallsBackToOneHour() throws IOException {
		MovableClock clock = new MovableClock();
		CountingSource source = new CountingSource(clock);
		source.expiresIn = null;
		source.accessToken();

		clock.advance(AbstractCachingTokenSource.FALLBACK_TTL_MS - AbstractCachingTokenSource.SKEW_MS - 1000);
		assertThat(source.accessToken()).isEqualTo("token-1");
	}

	@Test
	public void testInvalidateForcesARefetch() throws IOException {
		CountingSource source = new CountingSource(new MovableClock());
		source.accessToken();
		source.invalidate();

		assertThat(source.accessToken()).isEqualTo("token-2");
	}

	@Test
	public void testConcurrentCallersTriggerExactlyOneFetch() throws Exception {
		CountingSource source = new CountingSource(new MovableClock());
		source.gate = new CountDownLatch(1);

		int threads = 32;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					ready.countDown();
					go.await();
					return source.accessToken();
				});
			}
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			source.gate.countDown();

			pool.shutdown();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}

		// A worker runs many node tasks at once; without the double-checked lock an expiry would
		// stampede the token endpoint.
		assertThat(source.fetches).hasValue(1);
	}
}
