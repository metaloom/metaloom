package io.metaloom.cortex.node.watermark;

import io.metaloom.cortex.fs.AtomicFiles;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.node.watermark.WatermarkGeometry.Placement;

/**
 * The watermark node's video path: everything that shells out to {@code ffmpeg} / {@code ffprobe}.
 *
 * <p>
 * This is deliberately the <strong>only</strong> class in the node that starts a process. Nothing else under {@code cortex/} execs an external binary
 * (the sole {@code ProcessBuilder} in the repository is the agent sandbox's Podman backend), so the handling that a long-lived worker needs - a wall
 * clock, forcible termination, bounded stderr capture, no inherited stdin - is written once and kept here.
 * </p>
 *
 * <h2>Why ffprobe rather than video4j</h2>
 *
 * <p>
 * The only thing the video path needs from the container is the frame size, so that {@link WatermarkGeometry} can turn the relative options into pixels.
 * {@code video4j}'s {@code VideoFile.width()/height()} would answer that, but it drags the OpenCV native runtime into this module. {@code ffprobe} ships
 * with the {@code ffmpeg} the node already requires, so the module stays pure JDK plus one external binary.
 * </p>
 *
 * <p>
 * Resolving the geometry in Java also keeps the filter graph free of {@code scale2ref}, which is deprecated in ffmpeg 7 - the graph only ever sees
 * integers.
 * </p>
 */
public class FfmpegRunner {

	private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);

	/** How many trailing stderr lines to keep for a failure message. ffmpeg is verbose and the useful line is always near the end. */
	private static final int STDERR_TAIL_LINES = 20;

	private final String ffmpegPath;
	private final String ffprobePath;
	private final long timeoutMs;

	/** Cached availability probe. {@code null} until the first {@link #isAvailable()} call; {@code volatile} because nodes run on many threads. */
	private volatile Boolean available;

	/**
	 * @param ffmpegPath  the ffmpeg executable, resolved on {@code PATH} when it is a bare name
	 * @param ffprobePath the ffprobe executable
	 * @param timeoutMs   wall-clock budget for a single invocation
	 */
	public FfmpegRunner(String ffmpegPath, String ffprobePath, long timeoutMs) {
		this.ffmpegPath = ffmpegPath;
		this.ffprobePath = ffprobePath;
		this.timeoutMs = timeoutMs;
	}

	/** The coded frame dimensions of a video's first video stream. */
	public record VideoDimensions(int width, int height) {
	}

	/**
	 * Whether the ffmpeg binary can be started at all. Probed once and cached - the answer is a property of the worker's installation, not of the item.
	 *
	 * @return true when {@code ffmpeg -version} ran successfully
	 */
	public boolean isAvailable() {
		Boolean cached = available;
		if (cached != null) {
			return cached;
		}
		boolean result;
		try {
			result = run(List.of(ffmpegPath, "-version"), 10_000).exitCode() == 0;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.debug("ffmpeg is not available at '{}'", ffmpegPath, e);
			result = false;
		}
		available = result;
		return result;
	}

	/**
	 * Read the coded width and height of the first video stream.
	 *
	 * <p>
	 * ⚠️ These are the <em>coded</em> dimensions. A clip carrying rotation side-data or a non-square sample aspect ratio displays at different dimensions,
	 * and the overlay is then placed against coded rather than displayed geometry. Documented rather than handled - see the node's spec.
	 * </p>
	 *
	 * @param input the video file
	 * @return its dimensions
	 * @throws IOException when ffprobe fails or reports something unparseable
	 */
	public VideoDimensions probe(Path input) throws IOException {
		List<String> command = List.of(ffprobePath, "-v", "error", "-select_streams", "v:0",
			"-show_entries", "stream=width,height", "-of", "csv=p=0", input.toString());
		Result result = runChecked(command, "ffprobe");
		// `csv=p=0` yields exactly "width,height"; a file with no video stream yields an empty line rather than an error exit.
		String line = result.output().lines()
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.findFirst()
			.orElseThrow(() -> new IOException("ffprobe reported no video stream for " + input));
		String[] parts = line.split(",");
		if (parts.length < 2) {
			throw new IOException("Could not parse ffprobe dimensions '" + line + "' for " + input);
		}
		try {
			int width = Integer.parseInt(parts[0].trim());
			int height = Integer.parseInt(parts[1].trim());
			if (width <= 0 || height <= 0) {
				throw new IOException("ffprobe reported non-positive dimensions " + width + "x" + height + " for " + input);
			}
			return new VideoDimensions(width, height);
		} catch (NumberFormatException e) {
			throw new IOException("Could not parse ffprobe dimensions '" + line + "' for " + input, e);
		}
	}

	/**
	 * Burn the watermark into the video and write the result to {@code target}.
	 *
	 * <p>
	 * The video stream must be re-encoded - an overlay changes pixels - but audio is stream-copied, so a watermarked clip keeps its original sound
	 * untouched and un-degraded. The {@code ?} on {@code -map 0:a?} makes the audio stream optional so a silent clip does not fail.
	 * </p>
	 *
	 * @param input     the source video
	 * @param watermark the overlay PNG on disk
	 * @param placement resolved pixel geometry against the source's coded dimensions
	 * @param opacity   overlay alpha, <code>0.0</code>-<code>1.0</code>
	 * @param codec     video encoder, e.g. {@code libx264}
	 * @param crf       constant-rate-factor quality
	 * @param preset    encoder speed/size preset
	 * @param target    destination; written via a {@code .part} sibling and moved into place
	 * @throws IOException when ffmpeg fails, times out, or produces nothing
	 */
	public void overlay(Path input, Path watermark, Placement placement, double opacity, String codec, int crf, String preset, Path target)
		throws IOException {
		Files.createDirectories(target.getParent());
		// Must keep the target's extension last - ffmpeg picks its muxer from it. See AtomicFiles.partFor.
		Path part = AtomicFiles.partFor(target);

		// colorchannelmixer scales the overlay's existing alpha, so a fully transparent pixel stays transparent at any opacity.
		String filter = String.format(Locale.ROOT,
			"[1:v]scale=%d:%d,format=rgba,colorchannelmixer=aa=%s[wm];[0:v][wm]overlay=%d:%d[v]",
			placement.width(), placement.height(), formatOpacity(opacity), placement.x(), placement.y());

		List<String> command = new ArrayList<>(List.of(
			ffmpegPath, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
			"-i", input.toString(),
			"-i", watermark.toString(),
			"-filter_complex", filter,
			"-map", "[v]", "-map", "0:a?",
			"-c:v", codec, "-crf", Integer.toString(crf), "-preset", preset,
			"-c:a", "copy",
			"-movflags", "+faststart",
			part.toString()));

		try {
			runChecked(command, "ffmpeg");
			if (!Files.exists(part) || Files.size(part) == 0) {
				throw new IOException("ffmpeg reported success but produced no output for " + input);
			}
			AtomicFiles.move(part, target);
		} finally {
			Files.deleteIfExists(part);
		}
	}

	/**
	 * Format the opacity for the filter graph. {@code Locale.ROOT} matters: a comma decimal separator would split the filter argument and ffmpeg would
	 * reject the whole graph on a German-locale worker.
	 */
	private static String formatOpacity(double opacity) {
		return String.format(Locale.ROOT, "%.4f", Math.max(0d, Math.min(1d, opacity)));
	}

	private Result runChecked(List<String> command, String what) throws IOException {
		Result result;
		try {
			result = run(command, timeoutMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(what + " was interrupted", e);
		}
		if (result.exitCode() != 0) {
			throw new IOException(what + " exited with " + result.exitCode() + ": " + result.output().trim());
		}
		return result;
	}

	/**
	 * Start the process, drain its output on a separate thread and wait for it with a wall clock.
	 *
	 * <p>
	 * The output <strong>must</strong> be drained concurrently, not after {@code waitFor}: a process whose output pipe fills blocks forever, and ffmpeg on a
	 * long clip writes plenty. It must also not be drained on <em>this</em> thread, because {@code readLine} would then block until the child closed the
	 * pipe and the wall clock below could never fire - which is the one failure mode this method exists to bound. Only the trailing
	 * {@value #STDERR_TAIL_LINES} lines are retained, so a pathological run cannot exhaust heap either.
	 * </p>
	 */
	private Result run(List<String> command, long timeout) throws IOException, InterruptedException {
		log.debug("Running: {}", String.join(" ", command));
		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(nullDevice())))
			.start();

		Deque<String> tail = new ArrayDeque<>();
		Thread drain = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					synchronized (tail) {
						tail.addLast(line);
						if (tail.size() > STDERR_TAIL_LINES) {
							tail.removeFirst();
						}
					}
				}
			} catch (IOException e) {
				// Expected once the process is destroyed forcibly; there is nothing useful left to read.
				log.debug("Stopped reading output of {}", command.get(0), e);
			}
		}, "watermark-ffmpeg-drain");
		drain.setDaemon(true);
		drain.start();

		try {
			if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
				throw new IOException("Timed out after " + timeout + "ms running " + command.get(0));
			}
		} finally {
			// The child has exited (or been killed), so the pipe is at EOF and the drain thread is about to finish. Bounded join so a wedged reader can
			// never hold the node's thread.
			drain.join(5_000);
		}
		synchronized (tail) {
			return new Result(process.exitValue(), String.join(System.lineSeparator(), tail));
		}
	}

	/**
	 * stdin is redirected from the null device on top of {@code -nostdin}. Both are needed: {@code -nostdin} stops ffmpeg reading keystrokes, but a worker
	 * started from a terminal would otherwise still hand the child its controlling tty.
	 */
	private static String nullDevice() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null";
	}

	private record Result(int exitCode, String output) {
	}
}
