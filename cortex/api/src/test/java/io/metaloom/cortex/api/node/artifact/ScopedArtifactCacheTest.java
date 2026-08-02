package io.metaloom.cortex.api.node.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.artifact.ArtifactCache.Publication;
import io.metaloom.cortex.api.node.artifact.impl.ScopedArtifactCache;

/**
 * The mechanism, apart from the runner that opens it.
 *
 * <p>
 * Every case here is one of the four questions the design had to answer — who owns it, how long it lives, what a failure does to it, and what stops
 * it growing — asked of the object rather than of the segment.
 * </p>
 */
public class ScopedArtifactCacheTest {

	private static final ArtifactKey<String> FRAMES = ArtifactKey.of("video/frames@2fps", String.class);
	private static final ArtifactKey<String> AUDIO = ArtifactKey.of("audio/pcm@16k", String.class);

	/** Stands in for anything holding memory the collector cannot promptly reclaim. */
	private static class Closeable implements AutoCloseable {

		final String name;
		boolean closed;

		Closeable(String name) {
			this.name = name;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private static Artifact<String> weighing(String value, long bytes) {
		return Artifact.of(value, bytes);
	}

	@Test
	void testTheFactoryRunsOnceAndEverySubsequentCallerGetsThatSameObject() {
		AtomicInteger decodes = new AtomicInteger();
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1")) {
			String first = cache.get(FRAMES, () -> weighing("frames-" + decodes.incrementAndGet(), 10));
			String second = cache.get(FRAMES, () -> weighing("frames-" + decodes.incrementAndGet(), 10));

			assertEquals(1, decodes.get(), "The expensive work must happen once per key per scope");
			assertSame(first, second, "The second caller gets the artifact, not a copy of it");
		}
	}

	@Test
	void testAFactoryThatFailsPublishesNothingAndTheNextCallerMayTryAgain() {
		AtomicInteger attempts = new AtomicInteger();
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1")) {
			ArtifactException failure = assertThrows(ArtifactException.class, () -> cache.get(FRAMES, () -> {
				attempts.incrementAndGet();
				throw new java.io.IOException("the file moved");
			}));
			assertEquals("the file moved", failure.getCause().getMessage());
			assertTrue(cache.peek(FRAMES).isEmpty(), "A failed factory must leave nothing behind");

			// Not poisoned: a failure is not a cached negative result.
			assertEquals("frames", cache.get(FRAMES, () -> {
				attempts.incrementAndGet();
				return weighing("frames", 10);
			}));
			assertEquals(2, attempts.get());
		}
	}

	@Test
	void testARuntimeFailureInAFactoryReachesTheCallerUnwrapped() {
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1")) {
			// A node's own bug should read as its own bug in the log, not as an artifact problem.
			IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> cache.get(FRAMES, () -> {
					throw new IllegalStateException("boom");
				}));
			assertEquals("boom", e.getMessage());
		}
	}

	@Test
	void testAFactoryThatAsksForItsOwnKeyFailsInsteadOfRecursing() {
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1")) {
			// Without the guard this is a StackOverflowError with a thousand identical frames.
			assertThrows(ArtifactException.class,
				() -> cache.get(FRAMES, () -> weighing(cache.get(FRAMES, () -> weighing("inner", 1)), 1)));
		}
	}

	@Test
	void testTwoArtifactsSharingOneKeyIdButNotItsTypeStayApart() {
		ArtifactKey<String> asString = ArtifactKey.of("shared", String.class);
		ArtifactKey<Integer> asInteger = ArtifactKey.of("shared", Integer.class);
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1")) {
			assertEquals("text", cache.get(asString, () -> weighing("text", 4)));

			// The type is part of the key's identity, so this is a second artifact rather than a
			// reinterpretation of the first. Each node pays for its own, and neither is handed an
			// object of a type it did not ask for.
			assertEquals(1, cache.get(asInteger, () -> Artifact.of(1, 4)));
			assertEquals("text", cache.peek(asString).orElseThrow());
		}
	}

	@Test
	void testPeekNeverBuildsTheArtifact() {
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1")) {
			assertTrue(cache.peek(FRAMES).isEmpty());
			cache.get(FRAMES, () -> weighing("frames", 10));
			assertEquals("frames", cache.peek(FRAMES).orElseThrow());
		}
	}

	@Test
	void testClosingTheScopeClosesWhatItStillHolds() {
		Closeable frames = new Closeable("frames");
		ScopedArtifactCache cache = new ScopedArtifactCache("item-1");
		ArtifactKey<Closeable> key = ArtifactKey.of("video/frames", Closeable.class);
		cache.get(key, () -> Artifact.of(frames, 10));

		cache.close();

		// The reason a native-backed artifact is safe to cache at all: the segment ending
		// releases it, rather than leaving it to a collector that does not know it is holding
		// two hundred megabytes outside the heap.
		assertTrue(frames.closed, "An AutoCloseable artifact must be released when its scope ends");
		assertEquals(0, cache.retainedBytes());
	}

	@Test
	void testAScopeUsedAfterItsExecutionFailsLoudly() {
		ScopedArtifactCache cache = new ScopedArtifactCache("item-1");
		cache.get(FRAMES, () -> weighing("frames", 10));
		cache.close();

		// A node that stashed the scope in a field is a leak waiting to happen; failing that
		// one task is better than silently serving item A's artifact to item B.
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> cache.get(FRAMES, () -> weighing("frames", 10)));
		assertTrue(e.getMessage().contains("closed"), e.getMessage());
	}

	@Test
	void testClosingTwiceIsHarmless() {
		Closeable frames = new Closeable("frames");
		ScopedArtifactCache cache = new ScopedArtifactCache("item-1");
		cache.get(ArtifactKey.of("f", Closeable.class), () -> Artifact.of(frames, 10));
		cache.close();
		cache.close();
		assertTrue(frames.closed);
	}

	@Test
	void testAnArtifactThatWillNotCloseDoesNotStopTheOthersBeingReleased() {
		Closeable good = new Closeable("good");
		ScopedArtifactCache cache = new ScopedArtifactCache("item-1");
		cache.get(ArtifactKey.of("bad", AutoCloseable.class), () -> Artifact.of(() -> {
			throw new IllegalStateException("will not release");
		}, 10));
		cache.get(ArtifactKey.of("good", Closeable.class), () -> Artifact.of(good, 10));

		cache.close();

		assertTrue(good.closed, "One leaky artifact must not strand the rest");
	}

	// ── the ceiling ──────────────────────────────────────────────────────

	@Test
	void testTheScopeStaysWithinItsCeilingByEvictingTheLeastRecentlyUsed() {
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1", 100)) {
			cache.get(ArtifactKey.of("a", String.class), () -> weighing("a", 40));
			cache.get(ArtifactKey.of("b", String.class), () -> weighing("b", 40));
			// Touch 'a' so 'b' becomes the least recently used, not merely the newer one.
			cache.peek(ArtifactKey.of("a", String.class));
			cache.get(ArtifactKey.of("c", String.class), () -> weighing("c", 40));

			assertTrue(cache.retainedBytes() <= cache.maxBytes(),
				"retained " + cache.retainedBytes() + " over a ceiling of " + cache.maxBytes());
			assertTrue(cache.peek(ArtifactKey.of("a", String.class)).isPresent(), "The recently used entry survives");
			assertTrue(cache.peek(ArtifactKey.of("b", String.class)).isEmpty(), "The least recently used one is evicted");
			assertTrue(cache.peek(ArtifactKey.of("c", String.class)).isPresent());
		}
	}

	@Test
	void testAnEvictedArtifactIsDroppedButNotClosed() {
		Closeable first = new Closeable("first");
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1", 100)) {
			cache.get(ArtifactKey.of("a", Closeable.class), () -> Artifact.of(first, 60));
			cache.get(ArtifactKey.of("b", Closeable.class), () -> Artifact.of(new Closeable("second"), 60));

			assertTrue(cache.peek(ArtifactKey.of("a", Closeable.class)).isEmpty(), "Evicted to make room");
			// Deliberate: the node that fetched it a moment ago may still be reading it, and
			// closing a native buffer out from under a running node is a segfault.
			assertFalse(first.closed, "A capacity eviction must not close an artifact somebody may still hold");
		}
	}

	@Test
	void testAnArtifactBiggerThanTheWholeCeilingIsReturnedButNotRetained() {
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1", 100)) {
			cache.get(ArtifactKey.of("small", String.class), () -> weighing("small", 50));
			String huge = cache.get(ArtifactKey.of("huge", String.class), () -> weighing("huge", 5000));

			assertEquals("huge", huge, "The caller still gets what it asked for");
			assertTrue(cache.peek(ArtifactKey.of("huge", String.class)).isEmpty(),
				"Storing it would evict everything and then be evicted itself");
			assertTrue(cache.peek(ArtifactKey.of("small", String.class)).isPresent(),
				"...and taking the rest of the scope down with it would be worse than not caching it");
		}
	}

	@Test
	void testInvalidateReleasesTheArtifactAndTheBudgetItHeld() {
		Closeable frames = new Closeable("frames");
		ArtifactKey<Closeable> key = ArtifactKey.of("f", Closeable.class);
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1", 100)) {
			cache.get(key, () -> Artifact.of(frames, 60));
			cache.invalidate(key);

			assertEquals(0, cache.retainedBytes());
			// Unlike an eviction, this one is a caller saying the artifact is finished with.
			assertTrue(frames.closed);
		}
	}

	// ── publication windows ──────────────────────────────────────────────

	@Test
	void testACommittedPublicationKeepsWhatTheNodePublished() {
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1")) {
			try (Publication publication = cache.beginPublication()) {
				cache.get(FRAMES, () -> weighing("frames", 10));
				publication.commit();
			}
			assertTrue(cache.peek(FRAMES).isPresent());
		}
	}

	@Test
	void testAnUncommittedPublicationTakesBackOnlyWhatThatNodePublished() {
		Closeable frames = new Closeable("frames");
		ArtifactKey<Closeable> framesKey = ArtifactKey.of("video/frames", Closeable.class);
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1")) {
			try (Publication first = cache.beginPublication()) {
				cache.get(AUDIO, () -> weighing("audio", 10));
				first.commit();
			}
			try (Publication second = cache.beginPublication()) {
				cache.get(framesKey, () -> Artifact.of(frames, 10));
				// No commit: the node failed.
			}

			assertTrue(cache.peek(framesKey).isEmpty(), "What the failed node published is discarded");
			assertTrue(frames.closed, "...and released, since the node that built it is gone");
			assertTrue(cache.peek(AUDIO).isPresent(), "What an earlier successful node published is untouched");
			assertEquals(10, cache.retainedBytes());
		}
	}

	@Test
	void testReadingAnEarlierArtifactDoesNotMakeItPartOfThisNodesPublication() {
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1")) {
			try (Publication first = cache.beginPublication()) {
				cache.get(AUDIO, () -> weighing("audio", 10));
				first.commit();
			}
			try (Publication second = cache.beginPublication()) {
				cache.get(AUDIO, () -> weighing("audio", 10));
				// The window records what a node added, not what it read - otherwise one
				// failure would take down every artifact it happened to touch.
			}
			assertTrue(cache.peek(AUDIO).isPresent());
		}
	}

	@Test
	void testTwoOverlappingPublicationsAreARunnerBugAndSaySo() {
		try (ScopedArtifactCache cache = new ScopedArtifactCache("item-1")) {
			try (Publication open = cache.beginPublication()) {
				assertThrows(IllegalStateException.class, cache::beginPublication);
			}
			// ...and the window is released again afterwards.
			assertNotNull(cache.beginPublication());
		}
	}

	// ── across many scopes ───────────────────────────────────────────────

	@Test
	void testALongRunOfScopesRetainsNothingOnceEachHasEnded() {
		List<Closeable> produced = new ArrayList<>();
		ArtifactKey<Closeable> key = ArtifactKey.of("video/frames", Closeable.class);

		for (int item = 0; item < 500; item++) {
			try (ScopedArtifactCache cache = new ScopedArtifactCache("item-" + item)) {
				Closeable artifact = new Closeable("frames-" + item);
				produced.add(artifact);
				cache.get(key, () -> Artifact.of(artifact, 200L * 1024 * 1024));
			}
		}

		// The bound across a run is not a size limit, it is the scope's lifetime: a worker
		// that has processed 500 items holds what a worker that has processed one holds.
		assertEquals(500, produced.size());
		assertTrue(produced.stream().allMatch(c -> c.closed),
			"Every scope released its artifact; nothing accumulates from one item to the next");
	}

	// ── the no-op scope ──────────────────────────────────────────────────

	@Test
	void testTheNoOpScopeComputesButRetainsNothing() {
		AtomicInteger decodes = new AtomicInteger();
		ArtifactCache cache = ArtifactCache.noop();

		cache.get(FRAMES, () -> weighing("frames-" + decodes.incrementAndGet(), 10));
		cache.get(FRAMES, () -> weighing("frames-" + decodes.incrementAndGet(), 10));

		// A node written against the artifact API has to work when nobody opened a scope -
		// standalone, in a test, from the CLI. It pays each time, which is the honest price
		// when there is no scope to bound the retention.
		assertEquals(2, decodes.get());
		assertEquals(0, cache.retainedBytes());
		assertTrue(cache.peek(FRAMES).isEmpty());
	}

	@Test
	void testTheNoOpScopeNeverClosesAnArtifactItDoesNotOwn() {
		Closeable frames = new Closeable("frames");
		ArtifactCache cache = ArtifactCache.noop();
		ArtifactKey<Closeable> key = ArtifactKey.of("f", Closeable.class);

		Closeable returned = cache.get(key, () -> Artifact.of(frames, 10));
		cache.close();

		assertSame(frames, returned);
		assertFalse(frames.closed, "The caller owns it - the no-op scope never took it");
	}
}
