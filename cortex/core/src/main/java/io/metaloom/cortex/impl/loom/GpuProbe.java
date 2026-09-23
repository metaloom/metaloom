package io.metaloom.cortex.impl.loom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GPU utilisation and video memory, for the fleet view and for placement.
 *
 * <h3>Why a subprocess</h3>
 *
 * <p>
 * There is no JDK API for this and no NVML binding on the cortex classpath, so the honest options are a JNI library nobody maintains or the tool
 * every driver installation already ships. {@code nvidia-smi} is the tool, it is the same numbers the operator reads by hand, and it costs a fork
 * every twenty seconds - the interval the status update runs on.
 * </p>
 *
 * <p>
 * Everything about this is optional. A worker with no GPU, a driver mismatch, a container without the device, a machine where the binary is not on
 * the path: all of them return <code>null</code>, which the contract already means "unknown". They are not warnings either - a CPU-only worker is
 * the ordinary case, and logging one every twenty seconds would bury the log of a fleet that mostly has no cards in it.
 * </p>
 *
 * <h3>Why the sum, over several cards</h3>
 *
 * <p>
 * Utilisation is the busiest device and memory is the total, and the asymmetry is deliberate. One saturated card stalls every task that wants a GPU,
 * so averaging it against an idle sibling hides exactly the worker that should stop receiving work - the same reasoning
 * {@link SystemLoadProbe#ioLoad()} applies to disks. Memory, by contrast, is a pool a scheduler can draw from, so what a reader wants is how much of
 * the box is left.
 * </p>
 */
public class GpuProbe {

	private static final Logger log = LoggerFactory.getLogger(GpuProbe.class);

	/** Overridable so an operator can point at a wrapper, or at a binary off the path. */
	public static final String BINARY_PROPERTY = "loom.cortex.nvidiaSmiPath";

	private static final String DEFAULT_BINARY = "nvidia-smi";

	/** Mebibytes, which is the unit {@code nvidia-smi --format=nounits} prints memory in. */
	private static final long MIB = 1024L * 1024L;

	/** A hung driver must not hold the status update up; the probe answers "unknown" instead. */
	private static final long TIMEOUT_SECONDS = 5;

	/**
	 * Shortest interval between two forks.
	 *
	 * <p>
	 * Two callers poll this on unrelated schedules - the status update and the metrics scrape - and a fork per call would mean one every few seconds
	 * on a busy worker for a figure that changes on the order of a second anyway.
	 * </p>
	 */
	static final long MIN_SAMPLE_INTERVAL_MS = 2_000;

	/** What one {@code nvidia-smi} run said. Every field may be absent. */
	public record GpuStatus(Double load, Long memoryUsed, Long memoryTotal, String name) {

		static final GpuStatus UNKNOWN = new GpuStatus(null, null, null, null);
	}

	private final List<String> command;
	private final LongSupplier clockMillis;

	private GpuStatus last = GpuStatus.UNKNOWN;
	private long lastSampleAt = -1;

	public GpuProbe() {
		this(List.of(System.getProperty(BINARY_PROPERTY, DEFAULT_BINARY),
			"--query-gpu=utilization.gpu,memory.used,memory.total,name",
			"--format=csv,noheader,nounits"), System::currentTimeMillis);
	}

	GpuProbe(List<String> command, LongSupplier clockMillis) {
		this.command = List.copyOf(command);
		this.clockMillis = clockMillis;
	}

	/**
	 * @return the current reading, or a status whose every field is <code>null</code> when there is no GPU to read
	 */
	public synchronized GpuStatus status() {
		long now = clockMillis.getAsLong();
		if (lastSampleAt >= 0 && now - lastSampleAt < MIN_SAMPLE_INTERVAL_MS) {
			return last;
		}
		lastSampleAt = now;
		last = parse(run());
		return last;
	}

	private String run() {
		Process process = null;
		try {
			process = new ProcessBuilder(command).redirectErrorStream(false).start();
			// Read before waiting: a process whose pipe fills up never exits, and the output here is a
			// handful of lines only because the fleet is small.
			String out;
			try (var in = process.getInputStream()) {
				out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				log.debug("nvidia-smi did not answer within {}s", TIMEOUT_SECONDS);
				return null;
			}
			return process.exitValue() == 0 ? out : null;
		} catch (IOException e) {
			// No binary, no device, no permission. The ordinary case on a CPU worker.
			log.trace("No GPU metrics available", e);
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	/**
	 * Turn {@code nvidia-smi} CSV into a status.
	 *
	 * <p>
	 * One line per device: {@code <utilisation>, <used MiB>, <total MiB>, <name>}. A field the driver cannot answer prints as
	 * {@code [N/A]}, which parses as absent rather than as zero - a card reporting no utilisation is not an idle card.
	 * </p>
	 *
	 * @param csv
	 *            raw output, or <code>null</code> when the command did not run
	 */
	static GpuStatus parse(String csv) {
		if (csv == null || csv.isBlank()) {
			return GpuStatus.UNKNOWN;
		}
		Double busiest = null;
		Long used = null;
		Long total = null;
		List<String> names = new ArrayList<>();
		for (String line : csv.split("\\R")) {
			if (line.isBlank()) {
				continue;
			}
			String[] fields = line.split(",");
			if (fields.length < 3) {
				continue;
			}
			Double load = percent(fields[0]);
			if (load != null) {
				busiest = busiest == null ? load : Math.max(busiest, load);
			}
			Long deviceUsed = mebibytes(fields[1]);
			if (deviceUsed != null) {
				used = (used == null ? 0L : used) + deviceUsed;
			}
			Long deviceTotal = mebibytes(fields[2]);
			if (deviceTotal != null) {
				total = (total == null ? 0L : total) + deviceTotal;
			}
			if (fields.length >= 4 && !fields[3].isBlank()) {
				names.add(fields[3].trim());
			}
		}
		return new GpuStatus(busiest, used, total, describe(names));
	}

	/** "NVIDIA RTX 4090", or "2 × NVIDIA RTX 4090" when the box has more than one. */
	private static String describe(List<String> names) {
		if (names.isEmpty()) {
			return null;
		}
		return names.size() == 1 ? names.get(0) : names.size() + " × " + names.get(0);
	}

	private static Double percent(String field) {
		Double value = number(field);
		return value == null ? null : Math.min(100.0d, Math.max(0.0d, value));
	}

	private static Long mebibytes(String field) {
		Double value = number(field);
		return value == null ? null : (long) (value * MIB);
	}

	private static Double number(String field) {
		String trimmed = field == null ? "" : field.trim();
		if (trimmed.isEmpty() || trimmed.startsWith("[")) {
			// "[N/A]" and "[Not Supported]": the driver declined to answer.
			return null;
		}
		try {
			return Double.parseDouble(trimmed);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
