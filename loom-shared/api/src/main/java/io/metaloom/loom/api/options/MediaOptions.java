package io.metaloom.loom.api.options;

/**
 * Options for derived media: poster frames and browser-playable video streams (see spec/features/rest/REST_BINARY_HANDLING.md).
 *
 * <p>
 * Both are produced on demand by an external {@code ffmpeg}, because Loom stores originals and nothing else: the {@code attachment} table has had
 * {@code ASSET_THUMBNAIL}, {@code POSTER_FRAME} and {@code PROXY} since V2.44 and no producer has ever written one. Until that changes, a UI asking
 * for a preview of a 4.5 GB Matroska file was being handed the 4.5 GB file - which no browser can decode anyway.
 * </p>
 *
 * <p>
 * Like search and similarity, this is a capability rather than a dependency: when {@code ffmpeg} is missing the routes answer <b>503 with a
 * reason</b> and everything else keeps working. They must never fall back to a placeholder - a silent placeholder is exactly how the missing
 * thumbnail producer stayed invisible for so long.
 * </p>
 */
public class MediaOptions implements Option {

	/** Seconds into the video that a poster frame is taken from when the caller does not say. */
	public static final int DEFAULT_POSTER_SECOND = 5;

	/** Widest poster a caller may request. A poster is a grid tile, not a delivery format. */
	public static final int MAX_POSTER_WIDTH = 1920;

	@EnvironmentVariable(name = "LOOM_MEDIA_ENABLED", description = "Master switch for on-demand poster frames and video streaming. When off, both routes answer 503.")
	private boolean enabled = true;

	@EnvironmentVariable(name = "LOOM_MEDIA_FFMPEG_PATH", description = "Path to the ffmpeg binary used to extract poster frames and remux video. Answered with 503 when it is not executable.")
	private String ffmpegPath = "ffmpeg";

	@EnvironmentVariable(name = "LOOM_MEDIA_CACHE_PATH", description = "Directory holding cached poster frames. Content-addressed by asset hash, so it is safe to delete at any time.")
	private String cachePath = "data/media-cache";

	@EnvironmentVariable(name = "LOOM_MEDIA_MAX_STREAMS", description = "How many video streams may be remuxed at once. Further requests answer 503 rather than forking an unbounded number of ffmpeg processes.")
	private int maxConcurrentStreams = 4;

	@EnvironmentVariable(name = "LOOM_MEDIA_TOKEN_TTL", description = "Lifetime in seconds of a signed media token. Short on purpose: it travels in a URL, where it can be logged, shared or pasted.")
	private int tokenTtl = 600;

	public boolean isEnabled() {
		return enabled;
	}

	public MediaOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public String getFfmpegPath() {
		return ffmpegPath;
	}

	public MediaOptions setFfmpegPath(String ffmpegPath) {
		this.ffmpegPath = ffmpegPath;
		return this;
	}

	public String getCachePath() {
		return cachePath;
	}

	public MediaOptions setCachePath(String cachePath) {
		this.cachePath = cachePath;
		return this;
	}

	public int getMaxConcurrentStreams() {
		return maxConcurrentStreams;
	}

	public MediaOptions setMaxConcurrentStreams(int maxConcurrentStreams) {
		this.maxConcurrentStreams = maxConcurrentStreams;
		return this;
	}

	public int getTokenTtl() {
		return tokenTtl;
	}

	public MediaOptions setTokenTtl(int tokenTtl) {
		this.tokenTtl = tokenTtl;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		if (!enabled) {
			return;
		}
		if (ffmpegPath == null || ffmpegPath.isBlank()) {
			errors.add("ffmpegPath", "The ffmpeg path (LOOM_MEDIA_FFMPEG_PATH) must be set when media derivation is enabled.");
		}
		if (cachePath == null || cachePath.isBlank()) {
			errors.add("cachePath", "The media cache path (LOOM_MEDIA_CACHE_PATH) must be set when media derivation is enabled.");
		}
		errors.min("maxConcurrentStreams", maxConcurrentStreams, 1);
		errors.min("tokenTtl", tokenTtl, 1);
	}
}
