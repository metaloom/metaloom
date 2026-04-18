package io.metaloom.cortex.node.quality;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.metaloom.video4j.opencv.CVUtils;

public class QualityNode extends AbstractMediaNode<QualityNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(QualityNode.class);

	public static final NodeOutputKey<Double> OUTPUT_BLURRINESS = NodeOutputKey.of("blurriness", Double.class);
	public static final NodeOutputKey<Integer> OUTPUT_IMAGE_WIDTH = NodeOutputKey.of("image_width", Integer.class);
	public static final NodeOutputKey<Integer> OUTPUT_IMAGE_HEIGHT = NodeOutputKey.of("image_height", Integer.class);
	public static final NodeOutputKey<Integer> OUTPUT_VIDEO_WIDTH = NodeOutputKey.of("video_width", Integer.class);
	public static final NodeOutputKey<Integer> OUTPUT_VIDEO_HEIGHT = NodeOutputKey.of("video_height", Integer.class);
	public static final NodeOutputKey<Double> OUTPUT_VIDEO_FPS = NodeOutputKey.of("video_fps", Double.class);
	public static final NodeOutputKey<Long> OUTPUT_VIDEO_FRAME_COUNT = NodeOutputKey.of("video_frame_count", Long.class);
	public static final NodeOutputKey<String> OUTPUT_QUALITY_FLAG = NodeOutputKey.of("quality_flag", String.class);

	@Inject
	public QualityNode(@Nullable LoomClient client, CortexOptions cortexOption, QualityNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public void initialize() {
		Video4j.init();
	}

	@Override
	public String name() {
		return "quality";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		return media.isVideo() || media.isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		if (media.isImage()) {
			return processImage(ctx);
		} else if (media.isVideo()) {
			return processVideo(ctx);
		} else {
			return ctx.skipped("No visual media").next();
		}
	}

	private NodeResult processImage(NodeContext<LoomMedia> ctx) throws IOException {
		LoomMedia media = ctx.media();
		BufferedImage image = ImageIO.read(media.file());
		if (image == null) {
			return ctx.failure("Could not read image file").next();
		}

		QualityNodeOptions opts = options();

		// Resolution
		if (opts.isCheckResolution()) {
			ctx.output(OUTPUT_IMAGE_WIDTH, image.getWidth());
			ctx.output(OUTPUT_IMAGE_HEIGHT, image.getHeight());
		}

		// Blurriness via Laplacian operator
		if (opts.isCheckBlurriness()) {
			double blurriness = computeBlurriness(image);
			ctx.output(OUTPUT_BLURRINESS, blurriness);
		}

		ctx.output(OUTPUT_QUALITY_FLAG, "SUCCESS");
		return ctx.origin(COMPUTED).next();
	}

	private NodeResult processVideo(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		QualityNodeOptions opts = options();

		try (VideoFile video = Videos.open(media.absolutePath())) {
			// Video resolution and metadata
			if (opts.isCheckResolution()) {
				ctx.output(OUTPUT_VIDEO_WIDTH, video.width());
				ctx.output(OUTPUT_VIDEO_HEIGHT, video.height());
				ctx.output(OUTPUT_VIDEO_FPS, video.fps());
				ctx.output(OUTPUT_VIDEO_FRAME_COUNT, video.length());
			}

			// Blurriness check on a sample frame from the middle of the video
			if (opts.isCheckBlurriness()) {
				video.seekToFrameRatio(0.5);
				Mat frame = video.frameToMat();
				if (frame != null) {
					double blurriness = CVUtils.blurriness(frame);
					ctx.output(OUTPUT_BLURRINESS, blurriness);
					CVUtils.free(frame);
				}
			}

			ctx.output(OUTPUT_QUALITY_FLAG, "SUCCESS");
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to process video quality", e);
			return ctx.failure(e.getMessage()).next();
		}
	}

	/**
	 * Compute blurriness for a {@link BufferedImage} by converting it to a {@link Mat} first.
	 */
	private double computeBlurriness(BufferedImage image) {
		Mat mat = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC3);
		CVUtils.bufferedImageToMat(image, mat);
		double blurriness = CVUtils.blurriness(mat);
		CVUtils.free(mat);
		return blurriness;
	}
}
