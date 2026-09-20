package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET_BINARY;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.MediaOptions;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.auth.jwt.MediaTokenAuthHandler;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.media.MediaTokenResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.storage.BinaryStorage;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;

/**
 * Derived media: a poster frame and a browser-playable video stream, produced on demand by ffmpeg.
 *
 * <p>
 * <b>Why on demand.</b> Loom stores originals and nothing else. The {@code attachment} table has carried {@code ASSET_THUMBNAIL},
 * {@code POSTER_FRAME} and {@code PROXY} since V2.44 with no producer, and the Cortex {@code thumbnail} node writes its contact sheet to a
 * worker-local cache it never uploads. So every "thumbnail" in the UI was the original binary - for a 4.5 GB Matroska file, that URL is the 4.5 GB
 * file, and no browser can decode the container anyway.
 * </p>
 *
 * <p>
 * <b>The two shapes, and their costs.</b> A poster is one frame, cached content-addressed on disk, and is cheap enough to serve from a grid. A
 * stream is an ffmpeg process per viewer, held open for as long as they watch, and is bounded by a semaphore rather than left to fork freely. The
 * durable answer to both is a stored {@code PROXY}/{@code POSTER_FRAME} attachment written once by a node; this is what makes the UI work in the
 * meantime, and it is deliberately the kind of thing that shows up in a CPU graph rather than failing quietly.
 * </p>
 */
@Singleton
public class AssetMediaEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(AssetMediaEndpointService.class);

	/**
	 * How long to wait for ffmpeg to produce a single frame before giving up.
	 *
	 * <p>
	 * Seeking with {@code -ss} before {@code -i} is an index lookup rather than a decode, so this is generous by an order of magnitude even for a
	 * multi-gigabyte file on network storage. It exists so a wedged process cannot pin a worker thread forever.
	 * </p>
	 */
	private static final long POSTER_TIMEOUT_SECONDS = 30;

	/**
	 * Video codecs a stream copy can put into an MP4 container.
	 *
	 * <p>
	 * Only the video matters: the audio is re-encoded to AAC either way, which is cheap. A video codec outside this set would need a full
	 * re-encode, and that is a different feature with a different cost - so it is refused with a 415 rather than started silently.
	 * </p>
	 */
	private static final List<String> REMUXABLE_VIDEO_CODECS = List.of("h264");

	private final AssetDao assetDao;
	private final AssetBinaryDao binaryDao;
	private final BinaryStorageResolver storageResolver;
	private final AuthenticationService authService;
	private final LoomOptions options;
	private final Vertx vertx;

	/** Bounds concurrent ffmpeg stream processes; see {@link MediaOptions#getMaxConcurrentStreams()}. */
	private final Semaphore streamSlots;

	@Inject
	public AssetMediaEndpointService(AssetDao assetDao, AssetBinaryDao binaryDao, BinaryStorageResolver storageResolver,
		AuthenticationService authService, LoomOptions options, Vertx vertx, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.assetDao = assetDao;
		this.binaryDao = binaryDao;
		this.storageResolver = storageResolver;
		this.authService = authService;
		this.options = options;
		this.vertx = vertx;
		this.streamSlots = new Semaphore(Math.max(1, options.getMedia().getMaxConcurrentStreams()));
	}

	// ── Token ────────────────────────────────────────────────────────────

	/**
	 * Mint a short-lived token that authorises the media routes for one asset.
	 *
	 * <p>
	 * Needs {@code READ_ASSET_BINARY}, the same permission the bytes themselves need: the token is a way for an {@code <img>} tag to present a
	 * credential it cannot put in a header, not a way to widen who may read an asset.
	 * </p>
	 */
	public void mintToken(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET_BINARY, () -> {
			MediaOptions media = options.getMedia();
			Asset asset = assetDao.load(assetUuid);
			if (asset == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset not found.");
			}
			JsonObject claims = new JsonObject()
				.put("uuid", lrc.userUuid().toString())
				.put(MediaTokenAuthHandler.CLAIM_SCOPE, MediaTokenAuthHandler.SCOPE_MEDIA)
				.put(MediaTokenAuthHandler.CLAIM_ASSET, assetUuid.toString());
			String token = authService.generate(claims, media.getTokenTtl());
			lrc.send(new MediaTokenResponse().setToken(token).setExpiresIn(media.getTokenTtl()));
		});
	}

	// ── Poster ───────────────────────────────────────────────────────────

	/**
	 * A single frame of a video as a JPEG, cached on disk.
	 *
	 * @param second
	 *            offset to sample, or null for {@link MediaOptions#DEFAULT_POSTER_SECOND}
	 * @param width
	 *            target width, or null to keep the source width
	 */
	public void poster(LoomRoutingContext lrc, UUID assetUuid, Integer second, Integer width) {
		checkPerm(lrc, READ_ASSET_BINARY, () -> {
			MediaOptions media = requireMedia();
			int at = second == null ? MediaOptions.DEFAULT_POSTER_SECOND : Math.max(0, second);
			int w = width == null ? 0 : Math.min(Math.max(width, 16), MediaOptions.MAX_POSTER_WIDTH);

			Asset asset = assetDao.load(assetUuid);
			Path source = requireLocalSource(assetUuid);
			Path cached = posterCachePath(asset, media, at, w);

			HttpServerResponse response = lrc.routingContext().response();
			if (Files.isReadable(cached)) {
				sendPoster(response, cached);
				return;
			}

			vertx.executeBlocking(() -> renderPosterWithFallback(media, source, cached, at, w), false)
				.onSuccess(v -> sendPoster(response, cached))
				.onFailure(err -> {
					log.warn("Poster extraction failed for asset {}: {}", assetUuid, err.getMessage());
					if (!response.ended()) {
						response.setStatusCode(502).putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
							.end(new JsonObject().put("message", "Could not extract a poster frame: " + err.getMessage()).encode());
					}
				});
		});
	}

	private void sendPoster(HttpServerResponse response, Path cached) {
		response.putHeader(HttpHeaders.CONTENT_TYPE, "image/jpeg");
		// A poster is derived from immutable content at a fixed offset, so it never changes. The URL
		// carries a short-lived token, hence `private`: a shared cache must not keep it.
		response.putHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
		response.putHeader("Content-Disposition", "inline");
		response.sendFile(cached.toString());
	}

	/**
	 * Where a poster lives on disk.
	 *
	 * <p>
	 * Keyed by the asset's SHA-512 rather than its uuid, so two assets with identical content share one frame, and a cache wiped between restarts
	 * costs one ffmpeg call rather than correctness. The offset and width are part of the key because they are part of the picture.
	 * </p>
	 */
	private Path posterCachePath(Asset asset, MediaOptions media, int second, int width) {
		String key = asset != null && asset.getSHA512() != null ? asset.getSHA512().toString() : "unknown";
		String shard = key.length() >= 4 ? key.substring(0, 4) : "0000";
		return Paths.get(media.getCachePath(), "poster", shard, key + "-t" + second + "-w" + width + ".jpg");
	}

	/**
	 * Extract the requested frame, falling back to the first one.
	 *
	 * <p>
	 * Seeking past the end of a clip produces no frame and a non-zero exit, so a video shorter than
	 * the default offset would have no poster at all - and "shorter than five seconds" describes a
	 * lot of real media. Retrying at zero costs one extra ffmpeg call on a path that was going to
	 * fail anyway.
	 * </p>
	 */
	private Void renderPosterWithFallback(MediaOptions media, Path source, Path target, int second, int width)
		throws IOException, InterruptedException {
		try {
			return renderPoster(media, source, target, second, width);
		} catch (IOException e) {
			if (second == 0) {
				throw e;
			}
			log.debug("No frame at {}s in {}; falling back to the first frame", second, source);
			return renderPoster(media, source, target, 0, width);
		}
	}

	private Void renderPoster(MediaOptions media, Path source, Path target, int second, int width) throws IOException, InterruptedException {
		Files.createDirectories(target.getParent());
		Path tmp = Files.createTempFile(target.getParent(), "poster-", ".jpg.part");

		List<String> cmd = new ArrayList<>(List.of(
			media.getFfmpegPath(),
			// -ss BEFORE -i is the fast path: ffmpeg seeks by index instead of decoding up to the
			// offset. On a 4.5 GB file that is the difference between 100ms and several minutes.
			"-ss", String.valueOf(second),
			"-i", source.toString(),
			"-frames:v", "1"));
		if (width > 0) {
			// -2 keeps the aspect ratio and rounds to an even height, which JPEG encoders require.
			cmd.addAll(List.of("-vf", "scale=" + width + ":-2"));
		}
		cmd.addAll(List.of("-f", "image2", "-y", tmp.toString()));

		Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		String output = drain(process.getInputStream());
		boolean finished = process.waitFor(POSTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			Files.deleteIfExists(tmp);
			throw new IOException("ffmpeg timed out after " + POSTER_TIMEOUT_SECONDS + "s");
		}
		if (process.exitValue() != 0 || Files.size(tmp) == 0) {
			Files.deleteIfExists(tmp);
			throw new IOException("ffmpeg exited " + process.exitValue() + ": " + tail(output));
		}
		// Rename into place so a concurrent reader never sees a half-written frame.
		Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return null;
	}

	// ── Stream ───────────────────────────────────────────────────────────

	/**
	 * The video, remuxed into a fragmented MP4 a browser can play.
	 *
	 * <p>
	 * Video is stream-copied - these are already H.264, and re-encoding them per viewer is not a thing a media server should do - while audio is
	 * re-encoded, because the AC-3 track a .mkv typically carries is not playable from MP4. The result is a pipe, and a pipe has no index, so the
	 * player cannot seek within it: seeking is a new request with a different {@code t}, which is why that parameter exists.
	 * </p>
	 *
	 * @param second
	 *            where to start, for a client implementing seek-by-request
	 */
	public void stream(LoomRoutingContext lrc, UUID assetUuid, Integer second) {
		checkPerm(lrc, READ_ASSET_BINARY, () -> {
			MediaOptions media = requireMedia();
			int at = second == null ? 0 : Math.max(0, second);
			Path source = requireLocalSource(assetUuid);

			String codec = probeVideoCodec(media, source);
			if (codec != null && !REMUXABLE_VIDEO_CODECS.contains(codec)) {
				// Refused rather than transcoded. A full re-encode is minutes of CPU per viewer, and
				// starting one behind a chunked response would look like a slow stream rather than a
				// decision nobody made.
				throw new LoomRestException(415, LoomRestErrorCode.INTERNAL_ERROR,
					"This video is " + codec + "; only " + String.join("/", REMUXABLE_VIDEO_CODECS)
						+ " can be remuxed without re-encoding. Transcode it to H.264 to stream it here.");
			}

			if (!streamSlots.tryAcquire()) {
				// Better an honest refusal than a box with 50 ffmpeg processes on it.
				throw new LoomRestException(503, LoomRestErrorCode.INTERNAL_ERROR,
					"Too many video streams in flight. Try again in a moment.");
			}

			HttpServerResponse response = lrc.routingContext().response();
			response.putHeader(HttpHeaders.CONTENT_TYPE, "video/mp4");
			response.putHeader("Content-Disposition", "inline");
			// Fragmented MP4 over a pipe has no length and no index: chunked, and no range support.
			response.putHeader(HttpHeaders.ACCEPT_RANGES, "none");
			response.setChunked(true);

			vertx.executeBlocking(() -> pumpStream(media, source, at, response), false)
				.onComplete(ar -> {
					streamSlots.release();
					if (ar.failed()) {
						log.warn("Streaming failed for asset {}: {}", assetUuid, ar.cause().getMessage());
					}
					if (!response.ended()) {
						response.end();
					}
				});
		});
	}

	private Void pumpStream(MediaOptions media, Path source, int second, HttpServerResponse response) throws IOException, InterruptedException {
		List<String> cmd = new ArrayList<>(List.of(media.getFfmpegPath()));
		if (second > 0) {
			cmd.addAll(List.of("-ss", String.valueOf(second)));
		}
		cmd.addAll(List.of(
			"-i", source.toString(),
			"-map", "0:v:0", "-map", "0:a:0?",
			// Video passes through untouched; only the audio is re-encoded, because AC-3 in MP4 is
			// not something browsers play.
			"-c:v", "copy",
			"-c:a", "aac", "-b:a", "128k", "-ac", "2",
			// Fragmented MP4: playable from the first bytes, with no moov atom at the end that a
			// pipe could never reach.
			"-movflags", "frag_keyframe+empty_moov+default_base_moof",
			"-f", "mp4", "pipe:1"));

		Process process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try (InputStream in = process.getInputStream()) {
			byte[] chunk = new byte[64 * 1024];
			int read;
			while ((read = in.read(chunk)) != -1) {
				if (response.closed()) {
					// The viewer navigated away. Nothing downstream wants these bytes any more, and
					// the process must go with them rather than transcode into a closed socket.
					break;
				}
				response.write(Buffer.buffer(java.util.Arrays.copyOf(chunk, read)));
				while (response.writeQueueFull() && !response.closed()) {
					// Crude but correct backpressure: this runs on a worker thread, so sleeping here
					// blocks nothing but this one stream.
					Thread.sleep(10);
				}
			}
		} finally {
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		}
		return null;
	}

	// ── Shared ───────────────────────────────────────────────────────────

	/**
	 * The media capability, or a 503 saying which half is missing.
	 *
	 * <p>
	 * Never a placeholder. A silent fallback is precisely how the absent thumbnail producer stayed invisible through several releases.
	 * </p>
	 */
	private MediaOptions requireMedia() {
		MediaOptions media = options.getMedia();
		if (!media.isEnabled()) {
			throw new LoomRestException(503, LoomRestErrorCode.INTERNAL_ERROR,
				"Media derivation is disabled (LOOM_MEDIA_ENABLED=false).");
		}
		if (!ffmpegAvailable(media)) {
			throw new LoomRestException(503, LoomRestErrorCode.INTERNAL_ERROR,
				"No usable ffmpeg at '" + media.getFfmpegPath() + "' (LOOM_MEDIA_FFMPEG_PATH).");
		}
		return media;
	}

	/**
	 * The first video stream's codec name, or null when it cannot be determined.
	 *
	 * <p>
	 * Null means "carry on": ffprobe missing or unreadable output is not evidence that the file is un-remuxable, and refusing on a failed probe
	 * would break streaming for an unrelated reason.
	 * </p>
	 */
	private String probeVideoCodec(MediaOptions media, Path source) {
		String ffprobe = media.getFfmpegPath().endsWith("ffmpeg")
			? media.getFfmpegPath().substring(0, media.getFfmpegPath().length() - "ffmpeg".length()) + "ffprobe"
			: "ffprobe";
		try {
			Process probe = new ProcessBuilder(ffprobe,
				"-v", "error",
				"-select_streams", "v:0",
				"-show_entries", "stream=codec_name",
				"-of", "default=nw=1:nk=1",
				source.toString()).start();
			String out = drain(probe.getInputStream());
			if (!probe.waitFor(10, TimeUnit.SECONDS) || probe.exitValue() != 0) {
				return null;
			}
			String codec = out.strip();
			return codec.isEmpty() ? null : codec;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.debug("Could not probe the video codec of {}", source, e);
			return null;
		}
	}

	private boolean ffmpegAvailable(MediaOptions media) {
		try {
			Process probe = new ProcessBuilder(media.getFfmpegPath(), "-version")
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
			return probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return false;
		}
	}

	/**
	 * The asset's primary binary as a local file.
	 *
	 * <p>
	 * ffmpeg takes a path, so an object-store asset cannot be served this way without staging the whole object first - which for a 4.5 GB file
	 * would cost more than it saves. That case answers 501 rather than silently downloading gigabytes.
	 * </p>
	 */
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
			throw new LoomRestException(501, LoomRestErrorCode.INTERNAL_ERROR,
				"Derived media is only available for filesystem-backed assets.");
		}
		return local.get();
	}

	private static String drain(InputStream in) throws IOException {
		try (InputStream stream = in) {
			return new String(stream.readAllBytes());
		}
	}

	/** The last few lines of ffmpeg's output - the part that says what went wrong. */
	private static String tail(String output) {
		if (output == null || output.isBlank()) {
			return "no output";
		}
		String[] lines = output.strip().split("\n");
		int from = Math.max(0, lines.length - 3);
		return String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
	}
}
