package io.metaloom.cortex.node.watermark;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.node.watermark.FfmpegRunner.VideoDimensions;
import io.metaloom.cortex.node.watermark.WatermarkGeometry.Placement;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * Composites a configured watermark image onto the asset's own pixels.
 *
 * <p>
 * Unlike the analysis nodes it does not describe a property of the media, and unlike {@code imagegen} it does not invent new pixels - it <em>marks</em>
 * the media it was given. Images are composited with plain Graphics2D; video is handed to the {@code ffmpeg} overlay filter by {@link FfmpegRunner},
 * which re-encodes the video stream and stream-copies the audio.
 * </p>
 *
 * <p>
 * The source file is never modified. Following the {@code thumbnail}/{@code imagegen}/{@code depthmap} contract the result is written to a worker-local
 * cache under {@code metaPath/watermark_bin} and only the {@code asset_node_result} ledger entry is recorded in Loom - there is no byte-ingest endpoint
 * for produced media yet, so wire the artifact output into {@code s3-sink} to keep it.
 * </p>
 *
 * <p>
 * Exactly one of {@link #OUT_IMAGE} / {@link #OUT_VIDEO} is written per item, decided by the media kind. A downstream node wired to the other one simply
 * receives nothing for that item, which is how an image-only and a video-only branch are expressed without a filter node.
 * </p>
 */
@NodeSpec(nodeId = "watermark", name = "Watermark", icon = "branding_watermark", category = NodeCategory.TRANSFORM,
	description = "Composite a configured watermark image onto the asset - a still is redrawn with Graphics2D, a video is re-encoded "
		+ "through the ffmpeg overlay filter with its audio copied untouched. The source file is never modified; the marked copy is "
		+ "written to the worker's local cache, so wire it into a sink to keep it.",
	// timeoutMs lives on AbstractNodeOptions, where it is hidden because almost no descriptor advertises
	// it. This node does - a CPU re-encode needs a far larger budget than an API call - so it
	// re-documents the inherited field here, last in the form as the descriptor had it.
	parameters = @ParamOverride(key = "timeoutMs", label = "Timeout (ms)",
		description = "Wall-clock budget per item. A CPU video re-encode is far slower than an image composite",
		min = "1", order = 200))
public class WatermarkNode extends AbstractMediaNode<WatermarkNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(WatermarkNode.class);

	/** Bumped when the compositing itself changes meaning, so a cached or recorded result from an older build is visibly a different producer. */
	public static final String ALGORITHM_VERSION = "watermark/1";

	@PortDoc(label = "Media", description = "The image or video to mark. Audio and documents are skipped")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	@PortDoc(label = "Marked Image", description = "The watermarked PNG, written for image items only")
	public static final OutputPort<String> OUT_IMAGE = OutputPort.one("image", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);

	@PortDoc(label = "Marked Video", description = "The watermarked clip in the source container, written for video items only")
	public static final OutputPort<String> OUT_VIDEO = OutputPort.one("video", ContentTypeRegistry.ARTIFACT_VIDEO, String.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item")
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	private static final int RESULT_CACHE_SIZE = 10_000;

	/** In-heap skip cache of the artifact path, keyed by media path <em>and</em> the options that change the result. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final CortexOptions cortexOptions;

	@Inject
	public WatermarkNode(@Nullable LoomClient client, CortexOptions cortexOptions, WatermarkNodeOptions options) {
		super(client, cortexOptions, options);
		this.cortexOptions = cortexOptions;
	}

	@Override
	public String name() {
		return "watermark";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().isImage() || ctx.media().isVideo();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		boolean video = media.isVideo();
		OutputPort<String> artifactPort = video ? OUT_VIDEO : OUT_IMAGE;
		String cacheKey = cacheKey(media);

		// Re-emit a locally cached artifact instead of re-compositing. The file itself is checked: an artifact deleted from the cache directory between runs
		// would otherwise be handed downstream as a path that no longer resolves.
		String cached = resultCache.get(cacheKey);
		if (cached != null && Files.exists(Path.of(cached))) {
			ctx.output(OUT_FLAG, "DONE");
			ctx.output(artifactPort, cached);
			return ctx.origin(LOCAL).next();
		}

		try {
			BufferedImage watermark = WatermarkImages.decode(options().getWatermarkBase64());
			Path target = resolveArtifactPath(media, video);

			if (video) {
				applyToVideo(ctx, media, watermark, target);
			} else {
				applyToImage(media, watermark, target);
			}

			ctx.print("DONE", Files.size(target) + " bytes");
			ctx.output(OUT_FLAG, "DONE");
			ctx.output(artifactPort, target.toString());
			resultCache.put(cacheKey, target.toString());

			// Ledger only: the bytes live in the local watermark_bin cache. Uploading them into the asset binary subsystem needs a byte-ingest endpoint that
			// does not exist yet, so that remains a follow-up (same as thumbnail/imagegen/depthmap).
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion(), null);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to watermark media {}", media.absolutePath(), e);
			ctx.output(OUT_FLAG, "FAILED");
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion(), null);
			// abort(), not next(): NodeContextImpl.next() ignores the recorded failure cause and would report this run as SUCCESS with no artifact.
			return ctx.failure(e.getMessage()).abort();
		}
	}

	private void applyToImage(LoomMedia media, BufferedImage watermark, Path target) throws IOException {
		BufferedImage source = WatermarkImages.read(media.file());
		Placement placement = place(source.getWidth(), source.getHeight(), watermark);
		BufferedImage result = WatermarkImages.composite(source, watermark, placement, options().getOpacity());
		WatermarkImages.writePng(result, target);
	}

	private void applyToVideo(NodeContext<LoomMedia> ctx, LoomMedia media, BufferedImage watermark, Path target) throws IOException {
		FfmpegRunner runner = runner();
		if (!runner.isAvailable()) {
			// Fail rather than skip. A skip reads as "this item did not need watermarking", which would quietly ship un-watermarked video; the operator needs
			// to know the worker cannot do the job it was given.
			throw new IOException("ffmpeg is required to watermark video but could not be started as '" + options().getFfmpegPath()
				+ "'. Install ffmpeg or set the ffmpegPath option.");
		}
		Path source = Path.of(media.absolutePath());
		VideoDimensions dimensions = runner.probe(source);
		Placement placement = place(dimensions.width(), dimensions.height(), watermark);

		// The overlay has to reach ffmpeg as a file. It is written next to the artifact rather than into a shared temp directory so two concurrent
		// watermark nodes with different logos cannot overwrite each other's overlay mid-encode.
		Path overlay = target.resolveSibling(target.getFileName() + ".overlay.png");
		try {
			WatermarkImages.writePng(scaleForVideo(watermark, placement), overlay);
			ctx.info("Watermarking " + dimensions.width() + "x" + dimensions.height() + " video at " + placement.x() + "," + placement.y());
			runner.overlay(source, overlay, placement, options().getOpacity(), options().getVideoCodec(), options().getVideoCrf(),
				options().getVideoPreset(), target);
		} finally {
			Files.deleteIfExists(overlay);
		}
	}

	/**
	 * ffmpeg's {@code scale} filter resizes the overlay itself, so the PNG handed to it only needs to carry the right pixels - but writing it at the target
	 * size means Graphics2D does the resampling for both paths, and a video artifact's overlay is therefore identical to an image artifact's.
	 */
	private BufferedImage scaleForVideo(BufferedImage watermark, Placement placement) {
		BufferedImage canvas = new BufferedImage(placement.width(), placement.height(), BufferedImage.TYPE_INT_ARGB);
		return WatermarkImages.composite(canvas, watermark, new Placement(0, 0, placement.width(), placement.height()), 1.0d);
	}

	private Placement place(int mediaWidth, int mediaHeight, BufferedImage watermark) {
		return WatermarkGeometry.place(mediaWidth, mediaHeight, watermark.getWidth(), watermark.getHeight(),
			options().getScale(), options().getRelX(), options().getRelY());
	}

	/**
	 * Build the ffmpeg runner per invocation rather than injecting it: {@code timeoutMs} and the two executable paths are options, and a node's options are
	 * re-read per pipeline instance.
	 */
	private FfmpegRunner runner() {
		return new FfmpegRunner(options().getFfmpegPath(), options().getFfprobePath(), options().getTimeoutMs());
	}

	/**
	 * The artifact path: {@code metaPath/watermark_bin/<segment>/<sha512>-<optionsHash>.<ext>}.
	 *
	 * <p>
	 * The options hash is in the file name, not just in the cache key. Two {@code watermark} nodes in one graph - a logo bottom-right and a rating badge
	 * top-left, say - would otherwise write to the same path and each serve the other's output.
	 * </p>
	 */
	private Path resolveArtifactPath(LoomMedia media, boolean video) {
		SHA512 hash = media.getSHA512();
		String extension = video ? videoExtension(media) : ".png";
		String fileName = hash + "-" + optionsHash() + extension;
		Path basePath = cortexOptions.getMetaPath().resolve("watermark_bin");
		return HashUtils.segmentPath(basePath, hash).resolve(fileName);
	}

	/**
	 * Keep the source container. The extension is not cosmetic: ffmpeg picks the muxer from it, and {@code FilterHelper.isVideo} - which decides whether a
	 * downstream node sees a video at all - looks at nothing else.
	 */
	private static String videoExtension(LoomMedia media) {
		String name = media.path().getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : ".mp4";
	}

	private String cacheKey(LoomMedia media) {
		return media.absolutePath() + "|" + optionsHash();
	}

	/**
	 * A short digest of everything that changes the output pixels. The watermark itself is included by content, not by length: two different logos of the
	 * same size are the commonest way to get a stale artifact.
	 */
	private String optionsHash() {
		WatermarkNodeOptions o = options();
		String material = String.join("|",
			WatermarkImages.strip(o.getWatermarkBase64() == null ? "" : o.getWatermarkBase64()),
			Double.toString(o.getRelX()), Double.toString(o.getRelY()),
			Double.toString(o.getScale()), Double.toString(o.getOpacity()),
			o.getVideoCodec(), Integer.toString(o.getVideoCrf()), o.getVideoPreset());
		return sha256Hex(material).substring(0, 12);
	}

	/**
	 * {@code watermark/1:<watermark digest>} - the {@code ScriptNode} shape. A changed logo is a different producer, so the ledger records which mark was
	 * actually burned in rather than only that some watermark node ran.
	 */
	private String producerVersion() {
		String base64 = options().getWatermarkBase64();
		return ALGORITHM_VERSION + ":" + sha256Hex(WatermarkImages.strip(base64 == null ? "" : base64)).substring(0, 12);
	}

	private static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is mandated by the JLS for every conformant JRE.
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}
}
