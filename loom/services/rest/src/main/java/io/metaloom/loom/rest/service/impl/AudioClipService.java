package io.metaloom.loom.rest.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.MediaOptions;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;
import io.metaloom.loom.storage.BinaryStorage;

/**
 * Cuts stretches of speech out of an asset with ffmpeg, and measures recordings.
 *
 * <p>
 * Two callers need a piece of an asset's audio rather than the whole file: a reviewer listening to one speaker's turn, and importing that turn into a
 * person's voice samples. Both get the same thing - 16 kHz mono 16-bit WAV, which every browser plays and which is exactly what the voiceprint models
 * consume, so a sample cut here is never re-encoded on its way to a model.
 * </p>
 *
 * <p>
 * Shares its preconditions with the poster and stream routes: media derivation must be enabled, ffmpeg must be present, and the asset must be on a
 * filesystem-backed pool. Each missing precondition is answered with its own status and reason, never with an empty clip.
 * </p>
 */
@Singleton
public class AudioClipService {

	private static final Logger log = LoggerFactory.getLogger(AudioClipService.class);

	/** Longest clip one request may cut. A review clip is a turn, not a programme. */
	public static final long MAX_CLIP_MS = 120_000L;

	/** Generous for a two-minute cut from a local file; it exists so a wedged ffmpeg cannot pin a worker thread. */
	private static final int CUT_TIMEOUT_SECONDS = 60;

	private static final int PROBE_TIMEOUT_SECONDS = 15;

	/** A canonical PCM WAV header; a cut no longer than this holds no audio. */
	private static final int WAV_HEADER_BYTES = 44;

	private final AssetBinaryDao binaryDao;

	private final BinaryStorageResolver storageResolver;

	private final LoomOptions options;

	@Inject
	public AudioClipService(AssetBinaryDao binaryDao, BinaryStorageResolver storageResolver, LoomOptions options) {
		this.binaryDao = binaryDao;
		this.storageResolver = storageResolver;
		this.options = options;
	}

	/**
	 * Cut {@code [fromMs, toMs)} of the asset's primary binary into a temporary WAV. The caller owns the file and must delete it.
	 */
	public Path cut(UUID assetUuid, long fromMs, long toMs) {
		if (fromMs < 0 || toMs <= fromMs) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "A clip needs 0 <= from < to, got " + fromMs + " - " + toMs);
		}
		if (toMs - fromMs > MAX_CLIP_MS) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "A clip is at most " + (MAX_CLIP_MS / 1000) + " seconds long");
		}
		MediaOptions media = requireMedia();
		Path source = requireLocalSource(assetUuid);
		Path target = null;
		try {
			target = Files.createTempFile("loom-clip-", ".wav");
			List<String> cmd = List.of(media.getFfmpegPath(), "-v", "error", "-y",
				// -ss before -i: an index seek to the turn rather than decoding the hour before it. With an audio re-encode ffmpeg still trims to
				// the exact sample, so this is fast and precise at once.
				"-ss", seconds(fromMs), "-t", seconds(toMs - fromMs), "-i", source.toString(),
				"-vn", "-map", "0:a:0", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
				// No metadata and bit-exact muxing: the header is then exactly the 44 bytes of a canonical WAV, which is what lets an empty cut be
				// told from a short one below - ffmpeg otherwise appends a LIST chunk naming itself.
				"-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
				"-f", "wav", target.toString());
			Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String output = drain(process.getInputStream());
			if (!process.waitFor(CUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("ffmpeg timed out after " + CUT_TIMEOUT_SECONDS + "s");
			}
			if (process.exitValue() != 0) {
				// The common cause is an asset with no audio stream at all, which "0:a:0" names explicitly.
				throw new LoomRestException(422, LoomRestErrorCode.BAD_REQUEST, "Could not cut audio from this asset: " + tail(output));
			}
			if (Files.size(target) <= WAV_HEADER_BYTES) {
				// A bare WAV header: the range lies past the end of the recording.
				throw new LoomRestException(422, LoomRestErrorCode.BAD_REQUEST, "The asset has no audio in " + fromMs + " - " + toMs + " ms");
			}
			return target;
		} catch (LoomRestException e) {
			deleteQuietly(target);
			throw e;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			deleteQuietly(target);
			throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Cutting audio failed: " + e.getMessage());
		}
	}

	/**
	 * The recording's length in milliseconds, or null when it cannot be measured - media derivation off, ffprobe missing, or a file it cannot read.
	 * A length is a convenience for the UI, never a reason to refuse an upload.
	 */
	public Long durationMs(Path file) {
		MediaOptions media = options.getMedia();
		if (media == null || !media.isEnabled()) {
			return null;
		}
		try {
			Process probe = new ProcessBuilder(ffprobePath(media), "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1",
				file.toString()).start();
			String out = drain(probe.getInputStream()).strip();
			if (!probe.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) || probe.exitValue() != 0 || out.isEmpty()) {
				return null;
			}
			return Math.round(Double.parseDouble(out) * 1000d);
		} catch (IOException | InterruptedException | NumberFormatException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.debug("Could not measure the duration of {}", file, e);
			return null;
		}
	}

	private MediaOptions requireMedia() {
		MediaOptions media = options.getMedia();
		if (media == null || !media.isEnabled()) {
			throw new LoomRestException(503, LoomRestErrorCode.INTERNAL_ERROR, "Media derivation is disabled (LOOM_MEDIA_ENABLED=false).");
		}
		try {
			Process probe = new ProcessBuilder(media.getFfmpegPath(), "-version").redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			if (!probe.waitFor(5, TimeUnit.SECONDS) || probe.exitValue() != 0) {
				throw new IOException("exit " + probe.exitValue());
			}
		} catch (IOException | InterruptedException | IllegalThreadStateException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new LoomRestException(503, LoomRestErrorCode.INTERNAL_ERROR,
				"No usable ffmpeg at '" + media.getFfmpegPath() + "' (LOOM_MEDIA_FFMPEG_PATH).");
		}
		return media;
	}

	/** The asset's primary binary as a local file - the same rule, and the same answers, as the poster and stream routes. */
	private Path requireLocalSource(UUID assetUuid) {
		AssetBinary binary = binaryDao.loadPrimaryByAssetUuid(assetUuid);
		if (binary == null || binary.getPath() == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No binary found for asset.");
		}
		BinaryStorage storage = storageResolver.forPool(binary.getPoolUuid());
		String locator = binary.getPath();
		if (!storage.exists(locator)) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Binary file is missing in " + storage.describe() + ".");
		}
		Optional<Path> local = storage.localPath(locator);
		if (local.isEmpty()) {
			throw new LoomRestException(501, LoomRestErrorCode.INTERNAL_ERROR, "Audio clips are only available for filesystem-backed assets.");
		}
		return local.get();
	}

	/** Fixed notation with a dot: {@code Double.toString} would render a small offset as {@code 1.0E-4}, which ffmpeg reads as zero. */
	static String seconds(long ms) {
		return BigDecimal.valueOf(ms).divide(BigDecimal.valueOf(1000), 3, RoundingMode.DOWN).toPlainString();
	}

	private static String ffprobePath(MediaOptions media) {
		String ffmpeg = media.getFfmpegPath();
		return ffmpeg.endsWith("ffmpeg") ? ffmpeg.substring(0, ffmpeg.length() - "ffmpeg".length()) + "ffprobe" : "ffprobe";
	}

	private static String drain(InputStream in) throws IOException {
		try (InputStream stream = in) {
			return new String(stream.readAllBytes());
		}
	}

	private static String tail(String output) {
		if (output == null || output.isBlank()) {
			return "no output";
		}
		String[] lines = output.strip().split("\n");
		return String.join(" | ", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - 2), lines.length));
	}

	private static void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.debug("Could not delete {}", file, e);
		}
	}
}
