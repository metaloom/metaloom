package io.metaloom.cortex.node.voiceprint;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The node's only shell-out: {@code ffmpeg} demuxing a video's audio track to a 16 kHz mono PCM WAV before it is uploaded.
 *
 * <p>
 * The sidecar decodes video itself, so this is an optimisation and a courtesy, not a requirement of the contract: a worker should not ship a
 * multi-gigabyte video over the wire when the sidecar is going to throw the pictures away. Audio files are uploaded as they are. The process
 * handling (wall clock, forcible termination, bounded stderr capture, no inherited stdin) follows the watermark node's {@code FfmpegRunner}.
 * </p>
 */
public class AudioDemuxer {

	private static final Logger log = LoggerFactory.getLogger(AudioDemuxer.class);

	private static final int STDERR_TAIL_LINES = 20;

	private final String ffmpegPath;

	public AudioDemuxer(String ffmpegPath) {
		this.ffmpegPath = ffmpegPath;
	}

	/**
	 * Extract the first audio track of {@code input} as 16 kHz mono 16-bit PCM into {@code target}.
	 *
	 * @throws IOException when ffmpeg is missing, fails, or produces nothing (a video without an audio track)
	 */
	public void toWav(Path input, Path target, long timeoutMs) throws IOException {
		Files.createDirectories(target.getParent());
		List<String> command = List.of(ffmpegPath, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
			"-i", input.toString(), "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "wav", target.toString());
		log.debug("Running: {}", String.join(" ", command));
		Process process;
		try {
			process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(nullDevice())))
				.start();
		} catch (IOException e) {
			throw new IOException("ffmpeg is not available at '" + ffmpegPath + "' - a video's audio track cannot be demuxed", e);
		}
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
			} catch (IOException ignored) {
				// The process ended; whatever was captured is what we report.
			}
		}, "ffmpeg-demux-drain");
		drain.setDaemon(true);
		drain.start();
		try {
			long budget = timeoutMs > 0 ? timeoutMs : VoiceprintClient.DEFAULT_TIMEOUT_MS;
			if (!process.waitFor(budget, TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new IOException("ffmpeg exceeded " + budget + " ms demuxing " + input);
			}
			drain.join(5_000);
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("ffmpeg was interrupted", e);
		}
		String output;
		synchronized (tail) {
			output = String.join("\n", tail).trim();
		}
		if (process.exitValue() != 0) {
			throw new IOException("ffmpeg exited with " + process.exitValue() + " demuxing " + input + ": " + output);
		}
		if (!Files.exists(target) || Files.size(target) <= 44) {
			throw new IOException("ffmpeg produced no audio for " + input + (output.isEmpty() ? "" : ": " + output));
		}
	}

	private static String nullDevice() {
		return System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null";
	}
}
