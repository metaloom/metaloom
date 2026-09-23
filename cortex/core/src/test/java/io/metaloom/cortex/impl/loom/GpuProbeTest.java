package io.metaloom.cortex.impl.loom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.impl.loom.GpuProbe.GpuStatus;

/**
 * What the fleet view and the placement logic read off a worker's card.
 *
 * <p>The distinction these tests exist to protect is absent versus zero. A worker with no GPU, a
 * driver that declines to answer, and a card sitting genuinely idle are three different states,
 * and collapsing them means a CPU-only worker advertises itself as the emptiest GPU on the
 * fleet.</p>
 */
public class GpuProbeTest {

	private static final long MIB = 1024L * 1024L;

	@Test
	void testOneCard() {
		GpuStatus status = GpuProbe.parse("37, 2048, 24564, NVIDIA GeForce RTX 4090\n");
		assertEquals(37.0d, status.load(), 0.0001d);
		assertEquals(2048 * MIB, status.memoryUsed());
		assertEquals(24564 * MIB, status.memoryTotal());
		assertEquals("NVIDIA GeForce RTX 4090", status.name());
	}

	@Test
	void testSeveralCardsSumMemoryAndTakeTheBusiestLoad() {
		// Memory is a pool a scheduler draws from, so it sums. Utilisation is not: one saturated
		// card stalls every task that wants a GPU, and averaging it against an idle sibling hides
		// exactly the worker that should stop receiving work.
		GpuStatus status = GpuProbe.parse("""
			12, 1024, 24564, NVIDIA GeForce RTX 4090
			96, 8192, 24564, NVIDIA GeForce RTX 4090
			""");
		assertEquals(96.0d, status.load(), 0.0001d);
		assertEquals(9216 * MIB, status.memoryUsed());
		assertEquals(2 * 24564L * MIB, status.memoryTotal());
		assertEquals("2 × NVIDIA GeForce RTX 4090", status.name());
	}

	@Test
	void testNoOutputIsUnknownRatherThanIdle() {
		// The CPU-only worker. Every field absent, which the STATUS_UPDATE contract reads as
		// "unknown" - reporting 0% utilisation and 0 bytes used would advertise it as the most
		// attractive GPU target on the fleet.
		for (String empty : new String[] { null, "", "   \n" }) {
			GpuStatus status = GpuProbe.parse(empty);
			assertNull(status.load());
			assertNull(status.memoryUsed());
			assertNull(status.memoryTotal());
			assertNull(status.name());
		}
	}

	@Test
	void testDriverDeclinedFieldsAreAbsent() {
		// nvidia-smi prints "[N/A]" for a figure the driver will not give - notably utilisation on
		// several datacentre parts under MIG. The memory beside it is still real.
		GpuStatus status = GpuProbe.parse("[N/A], 512, 16384, Tesla T4");
		assertNull(status.load());
		assertEquals(512 * MIB, status.memoryUsed());
		assertEquals(16384 * MIB, status.memoryTotal());
	}

	@Test
	void testGarbageLinesAreSkippedRatherThanFatal() {
		GpuStatus status = GpuProbe.parse("not a csv line\n50, 100, 200, Card\n");
		assertEquals(50.0d, status.load(), 0.0001d);
		assertEquals(100 * MIB, status.memoryUsed());
	}

	@Test
	void testTheSubprocessIsNotForkedTwiceWithinTheSampleInterval() {
		// Two callers poll this on unrelated schedules: the status update every 20s and the
		// metrics scrape whenever Prometheus asks. A fork per call would be one every few seconds
		// for a figure that does not move that fast.
		AtomicLong clock = new AtomicLong(1_000);
		// A command that cannot exist, so a second run would be visibly a second run - the point
		// is that the cached object is returned identically rather than recomputed.
		GpuProbe probe = new GpuProbe(List.of("definitely-not-a-binary-" + System.nanoTime()), clock::get);

		GpuStatus first = probe.status();
		assertSame(first, probe.status(), "A second call inside the interval replays the sample");

		clock.addAndGet(GpuProbe.MIN_SAMPLE_INTERVAL_MS + 1);
		assertNull(probe.status().load(), "Still no card, and still not an exception");
	}
}
