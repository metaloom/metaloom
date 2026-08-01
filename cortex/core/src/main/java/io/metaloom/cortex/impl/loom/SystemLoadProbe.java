package io.metaloom.cortex.impl.loom;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Host CPU and disk-I/O utilisation, as the 0-100 percentages the
 * {@code STATUS_UPDATE} contract promises.
 *
 * <p>Both figures are what Loom places work on, so "roughly right" is not good
 * enough: a metric that is wrong in a consistent direction moves every task onto
 * the same worker. The two numbers are therefore derived the way the operating
 * system means them, and are reported as {@code null} - "unknown" - whenever they
 * cannot be, rather than as a plausible-looking substitute.</p>
 *
 * <p>The probe is stateful: I/O utilisation is a rate, so a single reading of the
 * kernel's counters says nothing. The first {@link #ioLoad()} call establishes the
 * baseline and returns unknown; every later call reports the interval since the
 * previous one.</p>
 */
public class SystemLoadProbe {

	private static final Logger log = LoggerFactory.getLogger(SystemLoadProbe.class);

	/** Per-device I/O counters. Linux-only; anywhere else I/O load stays unknown. */
	private static final Path PROC_DISKSTATS = Path.of("/proc/diskstats");

	/** 0-based index of "milliseconds spent doing I/O" in a {@code /proc/diskstats} line. */
	private static final int FIELD_BUSY_MILLIS = 12;

	/** Device names that are not backed by hardware and would double-count real I/O. */
	private static final Set<String> PSEUDO_DEVICE_PREFIXES = Set.of("loop", "ram", "zram", "fd", "sr");

	/** Shortest interval an I/O rate is measured over; below it the previous answer stands. */
	private static final long MIN_SAMPLE_INTERVAL_MS = 2_000;

	private final Path diskStats;
	private final LongSupplier clockMillis;

	/** Busy-millis per device as of {@link #lastSampleAt}; empty until the first sample. */
	private Map<String, Long> lastBusyMillis = Map.of();
	private long lastSampleAt = -1;
	/** Last utilisation computed, replayed for callers that arrive inside the sample interval. */
	private Double lastValue;

	public SystemLoadProbe() {
		this(PROC_DISKSTATS, System::currentTimeMillis);
	}

	/**
	 * @param diskStats   file in {@code /proc/diskstats} format
	 * @param clockMillis wall clock the I/O rate is measured against
	 */
	SystemLoadProbe(Path diskStats, LongSupplier clockMillis) {
		this.diskStats = diskStats;
		this.clockMillis = clockMillis;
	}

	/**
	 * @return percentage of total CPU capacity in use, or null when the platform will
	 *         not say
	 */
	public Double cpuLoad() {
		OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
		double recent = Double.NaN;
		if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
			recent = extended.getCpuLoad();
		}
		return cpuLoad(recent, os.getSystemLoadAverage(), os.getAvailableProcessors());
	}

	/**
	 * The CPU figure, split out from the MXBean so both branches are testable.
	 *
	 * @param recentCpuLoad system CPU load over the last interval as a 0..1 fraction;
	 *                      negative or NaN when unavailable
	 * @param loadAverage   one-minute run-queue length, negative when unavailable
	 * @param cores         processors available to this JVM
	 * @return 0-100 percentage, or null when neither source can produce one
	 */
	static Double cpuLoad(double recentCpuLoad, double loadAverage, int cores) {
		// The real measurement: a fraction of total capacity, already core-count aware.
		if (!Double.isNaN(recentCpuLoad) && recentCpuLoad >= 0) {
			return clampPercent(recentCpuLoad * 100.0d);
		}
		// Fallback only. The load average counts runnable tasks, so it is a percentage
		// of nothing until it is divided by the number of cores that can run them - the
		// missing division is what used to report a comfortable 4.0 on a 16-core box as
		// a saturated "100%".
		if (Double.isNaN(loadAverage) || loadAverage < 0 || cores <= 0) {
			return null;
		}
		return clampPercent(loadAverage / cores * 100.0d);
	}

	/**
	 * Disk utilisation since the previous call: the share of wall-clock time the busiest
	 * physical device spent with at least one request in flight - the same quantity
	 * {@code iostat} prints as {@code %util}.
	 *
	 * <p>The busiest device rather than the sum or the mean: one saturated disk stalls
	 * every task that touches it, and averaging it against idle peers hides exactly the
	 * worker that should stop receiving work.</p>
	 *
	 * @return 0-100 percentage, or null when there is no baseline yet or no counters to
	 *         read
	 */
	public synchronized Double ioLoad() {
		long now = clockMillis.getAsLong();
		if (lastSampleAt >= 0 && now - lastSampleAt < MIN_SAMPLE_INTERVAL_MS) {
			// Two callers poll this (the status update and the metrics scrape) on unrelated
			// schedules. Resampling on every call would hand whichever arrived second a
			// near-zero interval, and a rate measured over a few milliseconds is noise.
			return lastValue;
		}

		Map<String, Long> current;
		try {
			current = readBusyMillis(diskStats);
		} catch (IOException | RuntimeException e) {
			// Not an error: every non-Linux host lands here, and I/O load is optional.
			log.debug("Unable to read disk counters from {}", diskStats, e);
			return null;
		}

		Map<String, Long> previous = lastBusyMillis;
		long previousAt = lastSampleAt;
		lastBusyMillis = current;
		lastSampleAt = now;
		lastValue = utilisation(previous, previousAt, current, now);
		return lastValue;
	}

	/**
	 * Busiest-device utilisation between two samples of the busy-millis counters.
	 *
	 * @param previous   counters at {@code previousAt}
	 * @param previousAt when they were taken, negative when there is no earlier sample
	 * @param current    counters at {@code now}
	 * @param now        when they were taken
	 * @return 0-100 percentage, or null when the pair yields no usable interval
	 */
	static Double utilisation(Map<String, Long> previous, long previousAt, Map<String, Long> current, long now) {
		long elapsed = now - previousAt;
		if (previousAt < 0 || elapsed <= 0) {
			return null;
		}
		double busiest = -1;
		for (Map.Entry<String, Long> entry : current.entrySet()) {
			Long before = previous.get(entry.getKey());
			if (before == null) {
				// Device appeared since the last sample; it gets a rate next time round.
				continue;
			}
			long delta = entry.getValue() - before;
			if (delta < 0) {
				// The counter went backwards (device re-added, counter reset). Reporting a
				// negative rate as "idle" would make this worker look like the best target.
				continue;
			}
			busiest = Math.max(busiest, delta * 100.0d / elapsed);
		}
		return busiest < 0 ? null : clampPercent(busiest);
	}

	/**
	 * Read the per-device "milliseconds spent doing I/O" counters, keeping only whole
	 * physical devices.
	 *
	 * @param diskStats file in {@code /proc/diskstats} format
	 * @return busy millis by device name
	 */
	static Map<String, Long> readBusyMillis(Path diskStats) throws IOException {
		List<String> lines = Files.readAllLines(diskStats);
		Map<String, Long> byDevice = new HashMap<>();
		for (String line : lines) {
			String[] fields = line.trim().split("\\s+");
			if (fields.length <= FIELD_BUSY_MILLIS) {
				continue;
			}
			String device = fields[2];
			if (isPseudoDevice(device)) {
				continue;
			}
			try {
				byDevice.put(device, Long.parseLong(fields[FIELD_BUSY_MILLIS]));
			} catch (NumberFormatException e) {
				// A line we do not understand is skipped, not fatal: the remaining devices
				// still describe the host.
				log.debug("Ignoring unparsable disk stats line: {}", line);
			}
		}
		dropPartitions(byDevice);
		return byDevice;
	}

	private static boolean isPseudoDevice(String device) {
		for (String prefix : PSEUDO_DEVICE_PREFIXES) {
			if (device.startsWith(prefix) && isNumericSuffix(device.substring(prefix.length()))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Remove partitions, which report the same busy time as the disk carrying them and
	 * would otherwise be counted as separate devices.
	 *
	 * <p>A name is a partition when another listed device is a prefix of it and the rest
	 * is a partition number: {@code sda1} against {@code sda}, {@code nvme0n1p1} against
	 * {@code nvme0n1}. Deriving it from the listing rather than from a naming rule keeps
	 * {@code nvme0n1} - a whole device whose name also ends in a digit - in the set.</p>
	 */
	private static void dropPartitions(Map<String, Long> byDevice) {
		Set<String> devices = new HashSet<>(byDevice.keySet());
		byDevice.keySet().removeIf(name -> devices.stream().anyMatch(parent -> isPartitionOf(name, parent)));
	}

	private static boolean isPartitionOf(String name, String parent) {
		if (name.equals(parent) || !name.startsWith(parent)) {
			return false;
		}
		String suffix = name.substring(parent.length());
		if (suffix.startsWith("p")) {
			suffix = suffix.substring(1);
		}
		return isNumericSuffix(suffix);
	}

	private static boolean isNumericSuffix(String suffix) {
		if (suffix.isEmpty()) {
			return false;
		}
		for (int i = 0; i < suffix.length(); i++) {
			if (!Character.isDigit(suffix.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static double clampPercent(double value) {
		return Math.min(100.0d, Math.max(0.0d, value));
	}
}
