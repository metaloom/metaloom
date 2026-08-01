package io.metaloom.cortex.impl.loom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The numbers Loom places work on.
 *
 * <p>These are not cosmetic: a metric that is wrong in a consistent direction sends
 * every task to the same worker, which is why the load figures were previously
 * excluded from scheduling altogether.</p>
 */
public class SystemLoadProbeTest {

	@TempDir
	Path tmp;

	@Test
	void testCpuLoadUsesTheRecentLoadWhenAvailable() {
		// 0.42 of total capacity is 42%, whatever the core count.
		assertEquals(42.0d, SystemLoadProbe.cpuLoad(0.42d, 8.0d, 16), 0.0001d);
	}

	@Test
	void testCpuLoadFallbackDividesByCoreCount() {
		// The old calculation multiplied the raw load average by 100: a run queue of 4
		// on a 16-core box reported as a saturated 100% when the machine was a quarter
		// busy. It has to be per-core to mean anything.
		assertEquals(25.0d, SystemLoadProbe.cpuLoad(-1.0d, 4.0d, 16), 0.0001d);
		assertEquals(100.0d, SystemLoadProbe.cpuLoad(Double.NaN, 16.0d, 16), 0.0001d);
	}

	@Test
	void testCpuLoadClampsAnOverloadedMachine() {
		// A run queue longer than the core count is real, but the field is a percentage.
		assertEquals(100.0d, SystemLoadProbe.cpuLoad(-1.0d, 64.0d, 8), 0.0001d);
	}

	@Test
	void testCpuLoadIsUnknownRatherThanZeroWhenUnavailable() {
		// Reporting an unmeasurable CPU as 0% would make that worker the most attractive
		// target in the pool.
		assertNull(SystemLoadProbe.cpuLoad(-1.0d, -1.0d, 8));
		assertNull(SystemLoadProbe.cpuLoad(Double.NaN, 4.0d, 0));
	}

	@Test
	void testIoLoadIsTheBusiestDeviceOverElapsedTime() {
		// sda was busy for 500 ms of a 1000 ms interval, sdb for 100 ms. The stalled
		// disk is what matters; averaging it with the idle one hides it.
		Double load = SystemLoadProbe.utilisation(Map.of("sda", 1_000L, "sdb", 1_000L), 0,
			Map.of("sda", 1_500L, "sdb", 1_100L), 1_000);
		assertEquals(50.0d, load, 0.0001d);
	}

	@Test
	void testIoLoadHasNoValueUntilThereIsABaseline() {
		// A counter is not a rate: the first reading can only start the clock.
		assertNull(SystemLoadProbe.utilisation(Map.of(), -1, Map.of("sda", 10L), 1_000));
	}

	@Test
	void testIoLoadIgnoresACounterThatWentBackwards() {
		// A re-added device restarts its counter. A negative delta must not be reported
		// as an idle disk.
		assertNull(SystemLoadProbe.utilisation(Map.of("sda", 5_000L), 0, Map.of("sda", 10L), 1_000));
	}

	@Test
	void testIoLoadIgnoresADeviceWithoutABaseline() {
		// Hotplugged since the last sample: it gets a rate on the next round.
		Double load = SystemLoadProbe.utilisation(Map.of("sda", 0L), 0,
			Map.of("sda", 200L, "sdc", 9_000L), 1_000);
		assertEquals(20.0d, load, 0.0001d);
	}

	@Test
	void testIoLoadCannotExceedAHundredPercent() {
		Double load = SystemLoadProbe.utilisation(Map.of("sda", 0L), 0, Map.of("sda", 5_000L), 1_000);
		assertEquals(100.0d, load, 0.0001d);
	}

	@Test
	void testDiskStatsParsingKeepsWholeDevicesOnly() throws IOException {
		Path stats = writeDiskStats("""
			   7       0 loop0 12 0 96 4 0 0 0 0 0 40 4 0 0 0 0 0 0
			   8       0 sda 100 0 200 30 50 0 100 20 0 700 50 0 0 0 0 0 0
			   8       1 sda1 90 0 180 25 40 0 80 15 0 650 40 0 0 0 0 0 0
			 259       0 nvme0n1 10 0 20 3 5 0 10 2 0 900 5 0 0 0 0 0 0
			 259       1 nvme0n1p1 9 0 18 2 4 0 8 1 0 850 4 0 0 0 0 0 0
			""");

		Map<String, Long> busy = SystemLoadProbe.readBusyMillis(stats);

		// Partitions repeat their disk's busy time, and a loop device is a file dressed
		// as a disk - counting either would inflate the busiest-device figure.
		assertEquals(Map.of("sda", 700L, "nvme0n1", 900L), busy);
		// nvme0n1 ends in a digit but is a whole device; only names extending a listed
		// device are partitions.
		assertTrue(busy.containsKey("nvme0n1"));
		assertFalse(busy.containsKey("nvme0n1p1"));
	}

	@Test
	void testDiskStatsParsingSkipsUnusableLines() throws IOException {
		Path stats = writeDiskStats("""
			 8 0 sda 100 0 200 30 50 0 100 20 0 700 50 0 0 0 0 0 0
			 truncated line
			 8 16 sdb x x x x x x x x x notanumber x
			""");

		assertEquals(Map.of("sda", 700L), SystemLoadProbe.readBusyMillis(stats));
	}

	@Test
	void testIoLoadSamplesAcrossCallsAndDoesNotResampleTooOften() throws IOException {
		Path stats = writeDiskStats(" 8 0 sda 1 0 2 3 4 0 5 6 0 1000 7 0 0 0 0 0 0\n");
		AtomicLong clock = new AtomicLong(0);
		SystemLoadProbe probe = new SystemLoadProbe(stats, clock::get);

		assertNull(probe.ioLoad(), "The first sample is the baseline");

		clock.set(10_000);
		Files.writeString(stats, " 8 0 sda 1 0 2 3 4 0 5 6 0 3000 7 0 0 0 0 0 0\n");
		assertEquals(20.0d, probe.ioLoad(), 0.0001d);

		// The status update and the metrics scrape both poll this. A second caller
		// arriving milliseconds later must not measure a rate over that gap.
		clock.set(10_100);
		Files.writeString(stats, " 8 0 sda 1 0 2 3 4 0 5 6 0 3100 7 0 0 0 0 0 0\n");
		assertEquals(20.0d, probe.ioLoad(), 0.0001d);
	}

	@Test
	void testIoLoadIsUnknownWithoutCounters() {
		SystemLoadProbe probe = new SystemLoadProbe(tmp.resolve("absent"), () -> 0L);
		// Every non-Linux host lands here; it is a missing measurement, not a failure.
		assertNull(probe.ioLoad());
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void testTheProbeMeasuresThisMachine() {
		SystemLoadProbe probe = new SystemLoadProbe();

		// Wired end to end against the real MXBean and the real /proc/diskstats: a Linux
		// worker must always be able to say how loaded it is, or it drops out of
		// load-aware placement.
		Double cpu = probe.cpuLoad();
		assertNotNull(cpu, "CPU load unavailable on Linux");
		assertTrue(cpu >= 0.0d && cpu <= 100.0d, "CPU load out of range: " + cpu);
	}

	private Path writeDiskStats(String content) throws IOException {
		Path stats = tmp.resolve("diskstats");
		Files.writeString(stats, content);
		return stats;
	}
}
