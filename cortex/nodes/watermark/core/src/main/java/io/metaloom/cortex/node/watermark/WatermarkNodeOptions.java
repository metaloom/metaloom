package io.metaloom.cortex.node.watermark;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.loom.nodes.spec.ParameterType;

/**
 * Options for the {@link WatermarkNode}.
 *
 * <p>
 * The watermark image itself lives in {@link #watermarkBase64}, so a pipeline definition is self-contained: any worker can run the node without a shared
 * filesystem holding the logo. {@link #relX} / {@link #relY} place it, {@link #scale} sizes it relative to the media - see {@link WatermarkGeometry} for
 * exactly how the three are combined.
 * </p>
 *
 * <p>
 * The {@code video*} options only affect the video path, which re-encodes through ffmpeg. Images are always written as PNG.
 * </p>
 */
public class WatermarkNodeOptions extends AbstractNodeOptions<WatermarkNodeOptions> {

	public static final String KEY = "watermark";

	/** The watermark image. Bare base64 or a full {@code data:image/png;base64,...} URI; see {@link WatermarkImages#decode(String)}. */
	// Every field carries an explicit order because the node re-documents the inherited timeoutMs and
	// pins it last: an ordered parameter anywhere sorts the unordered ones behind it.
	@ParamDoc(label = "Watermark Image (base64)", type = ParameterType.CODE, rows = 6,
		description = "The overlay image, base64-encoded. A full data:image/png;base64,... URI is accepted too. "
			+ "Carrying the image here rather than as a path keeps the pipeline runnable on any worker",
		order = 100)
	private String watermarkBase64 = "";

	@ParamDoc(label = "Horizontal Position",
		description = "0.0 is flush left, 1.0 flush right, 0.5 centred. Measured against the space the overlay can slide in, "
			+ "so it never leaves the frame",
		min = "0.0", max = "1.0", step = "0.01", order = 110)
	private double relX = 0.95;

	@ParamDoc(label = "Vertical Position", description = "0.0 is flush top, 1.0 flush bottom, 0.5 centred",
		min = "0.0", max = "1.0", step = "0.01", order = 120)
	private double relY = 0.95;

	@ParamDoc(label = "Relative Size",
		description = "Overlay width as a fraction of the media width, aspect preserved. 0 keeps the overlay's own pixel size, "
			+ "which makes it look different on every resolution",
		min = "0.0", max = "1.0", step = "0.01", order = 130)
	private double scale = 0.20;

	@ParamDoc(label = "Opacity", description = "Scales the overlay's own transparency. 1.0 leaves it as authored",
		min = "0.01", max = "1.0", step = "0.05", order = 140)
	private double opacity = 1.0;

	@ParamDoc(label = "Video Codec",
		description = "Encoder for the video path. An overlay changes pixels, so the video stream must be re-encoded; audio is copied",
		order = 150)
	private String videoCodec = "libx264";

	@ParamDoc(label = "Video Quality (CRF)",
		description = "Constant rate factor. Lower is better quality and a larger file; 0 is lossless",
		min = "0", max = "51", order = 160)
	private int videoCrf = 23;

	@ParamDoc(label = "Encoder Preset", description = "Encoder speed/size trade-off, e.g. ultrafast, medium, slow", order = 170)
	private String videoPreset = "medium";

	@ParamDoc(label = "ffmpeg Path",
		description = "The ffmpeg executable, resolved on PATH when left as a bare name. Only the video path needs it",
		order = 180)
	private String ffmpegPath = "ffmpeg";

	@ParamDoc(label = "ffprobe Path", description = "The ffprobe executable, used to read the video's frame size", order = 190)
	private String ffprobePath = "ffprobe";

	public WatermarkNodeOptions() {
		// A re-encode is not an API call. Ten minutes is a realistic budget for a feature-length clip on a CPU encoder, where the sidecar-backed nodes get by
		// with two.
		setTimeoutMs(600_000);
	}

	@Override
	protected WatermarkNodeOptions self() {
		return this;
	}

	public String getWatermarkBase64() {
		return watermarkBase64;
	}

	public WatermarkNodeOptions setWatermarkBase64(String watermarkBase64) {
		this.watermarkBase64 = watermarkBase64;
		return this;
	}

	public double getRelX() {
		return relX;
	}

	public WatermarkNodeOptions setRelX(double relX) {
		this.relX = relX;
		return this;
	}

	public double getRelY() {
		return relY;
	}

	public WatermarkNodeOptions setRelY(double relY) {
		this.relY = relY;
		return this;
	}

	public double getScale() {
		return scale;
	}

	public WatermarkNodeOptions setScale(double scale) {
		this.scale = scale;
		return this;
	}

	public double getOpacity() {
		return opacity;
	}

	public WatermarkNodeOptions setOpacity(double opacity) {
		this.opacity = opacity;
		return this;
	}

	public String getVideoCodec() {
		return videoCodec;
	}

	public WatermarkNodeOptions setVideoCodec(String videoCodec) {
		this.videoCodec = videoCodec;
		return this;
	}

	public int getVideoCrf() {
		return videoCrf;
	}

	public WatermarkNodeOptions setVideoCrf(int videoCrf) {
		this.videoCrf = videoCrf;
		return this;
	}

	public String getVideoPreset() {
		return videoPreset;
	}

	public WatermarkNodeOptions setVideoPreset(String videoPreset) {
		this.videoPreset = videoPreset;
		return this;
	}

	public String getFfmpegPath() {
		return ffmpegPath;
	}

	public WatermarkNodeOptions setFfmpegPath(String ffmpegPath) {
		this.ffmpegPath = ffmpegPath;
		return this;
	}

	public String getFfprobePath() {
		return ffprobePath;
	}

	public WatermarkNodeOptions setFfprobePath(String ffprobePath) {
		this.ffprobePath = ffprobePath;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>(validateCommon());

		// Decodability is checked here rather than at first use: a typo in a pasted base64 blob is by far the likeliest misconfiguration, and
		// RegistryNodeRegistrar runs validate() at node construction, so the author learns about it when the pipeline starts instead of per item.
		if (watermarkBase64 == null || watermarkBase64.isBlank()) {
			errors.add("watermarkBase64 must not be empty");
		} else {
			try {
				java.util.Base64.getMimeDecoder().decode(WatermarkImages.strip(watermarkBase64));
			} catch (IllegalArgumentException e) {
				errors.add("watermarkBase64 is not valid base64: " + e.getMessage());
			}
		}

		if (relX < 0d || relX > 1d) {
			errors.add("relX must be within [0,1], got " + relX);
		}
		if (relY < 0d || relY > 1d) {
			errors.add("relY must be within [0,1], got " + relY);
		}
		if (scale < 0d || scale > 1d) {
			errors.add("scale must be within [0,1], got " + scale);
		}
		if (opacity <= 0d || opacity > 1d) {
			errors.add("opacity must be within (0,1], got " + opacity);
		}
		if (videoCrf < 0 || videoCrf > 51) {
			errors.add("videoCrf must be within [0,51], got " + videoCrf);
		}
		if (videoCodec == null || videoCodec.isBlank()) {
			errors.add("videoCodec must not be empty");
		}
		if (videoPreset == null || videoPreset.isBlank()) {
			errors.add("videoPreset must not be empty");
		}
		if (ffmpegPath == null || ffmpegPath.isBlank()) {
			errors.add("ffmpegPath must not be empty");
		}
		if (ffprobePath == null || ffprobePath.isBlank()) {
			errors.add("ffprobePath must not be empty");
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
