package io.metaloom.loom.api.uuid;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

public class LoomUUIDTest {

	@Test
	public void testVersionAndVariant() {
		for (int i = 0; i < 1000; i++) {
			UUID uuid = LoomUUID.timeOrdered();
			assertThat(uuid.version()).as("RFC 9562 version field").isEqualTo(7);
			assertThat(uuid.variant()).as("RFC 4122 variant field").isEqualTo(2);
		}
	}

	@Test
	public void testTimestampIsNow() {
		long before = System.currentTimeMillis();
		UUID uuid = LoomUUID.timeOrdered();
		long after = System.currentTimeMillis();
		// The generator may run one millisecond ahead of the clock after exhausting a counter, hence
		// the slack on the upper bound.
		assertThat(LoomUUID.timestampOf(uuid)).isBetween(before, after + 1);
	}

	@Test
	public void testTimestampOfRejectsOtherVersions() {
		assertThat(LoomUUID.timestampOf(UUID.randomUUID())).as("a v4 uuid carries no timestamp").isEqualTo(-1L);
		assertThat(LoomUUID.timestampOf(null)).isEqualTo(-1L);
	}

	/**
	 * The whole point of the switch: the ordering Postgres sees. Postgres compares uuid values as 16 unsigned bytes, which is <em>not</em> what
	 * {@link UUID#compareTo(UUID)} does - that one compares the halves as signed longs. Asserting on the byte order is therefore the only assertion
	 * that means anything here.
	 */
	@Test
	public void testUnsignedByteOrderIsIncreasing() {
		List<UUID> uuids = new ArrayList<>();
		for (int i = 0; i < 10_000; i++) {
			uuids.add(LoomUUID.timeOrdered());
		}
		for (int i = 1; i < uuids.size(); i++) {
			assertThat(compareUnsigned(uuids.get(i - 1), uuids.get(i)))
				.as("uuid %s must sort before %s", uuids.get(i - 1), uuids.get(i))
				.isNegative();
		}
	}

	/**
	 * 10 000 ids is far more than fits in the 12 bit intra millisecond counter, so this also covers the counter rolling over into the next
	 * millisecond.
	 */
	@Test
	public void testUnique() {
		Set<UUID> seen = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			assertThat(seen.add(LoomUUID.timeOrdered())).as("duplicate uuid at %d", i).isTrue();
		}
	}

	@Test
	public void testUniqueUnderConcurrency() throws Exception {
		int threads = 8;
		int perThread = 2_000;
		CountDownLatch start = new CountDownLatch(1);
		List<Set<UUID>> results = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<java.util.concurrent.Future<Set<UUID>>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				futures.add(pool.submit(() -> {
					start.await();
					Set<UUID> local = new HashSet<>();
					for (int i = 0; i < perThread; i++) {
						local.add(LoomUUID.timeOrdered());
					}
					return local;
				}));
			}
			start.countDown();
			for (java.util.concurrent.Future<Set<UUID>> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
		} finally {
			pool.shutdownNow();
		}

		Set<UUID> all = new HashSet<>();
		for (Set<UUID> local : results) {
			all.addAll(local);
		}
		assertThat(all).as("no collisions across %d threads", threads).hasSize(threads * perThread);
	}

	/**
	 * The 12 bit counter is seeded randomly on each new millisecond rather than reset to zero, so two runs a millisecond apart do not produce
	 * neighbouring ids. Without that, the low bits of an id would leak how many rows were written since the last tick.
	 */
	@Test
	public void testCounterIsSeededRandomly() throws InterruptedException {
		Set<Integer> seeds = new HashSet<>();
		for (int i = 0; i < 8; i++) {
			// The clock has to advance between samples, because the seed is drawn once per
			// millisecond. Spinning without this can finish every iteration inside one tick on a
			// fast machine, observe a single seed, and fail while the generator is behaving exactly
			// as intended — which is what it did.
			Thread.sleep(2);
			seeds.add((int) (LoomUUID.timeOrdered().getMostSignificantBits() & 0xFFF));
		}
		// Eight draws from a 1024 value seed space: identical seeds throughout would be a 1-in-10^21
		// coincidence, so this is a real assertion rather than a probabilistic one.
		assertThat(seeds).as("the per millisecond counter seed must vary").hasSizeGreaterThan(1);
	}

	private static int compareUnsigned(UUID a, UUID b) {
		return java.util.Arrays.compareUnsigned(toBytes(a), toBytes(b));
	}

	private static byte[] toBytes(UUID uuid) {
		return ByteBuffer.allocate(16)
			.putLong(uuid.getMostSignificantBits())
			.putLong(uuid.getLeastSignificantBits())
			.array();
	}
}
