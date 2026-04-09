package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;

import org.apache.avro.util.ByteBufferOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.node.facedetect.video.VideoFace;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScannerReport;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.payload.BoundingBox;
import io.metaloom.cortex.api.node.payload.Detection;
import io.metaloom.cortex.api.node.payload.DetectionPayload;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.cortex.node.facedetect.avro.Facedetection;
import io.metaloom.loom.cortex.node.facedetect.avro.FacedetectionBox;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.ByteBufferUtils;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.metaloom.video4j.utils.ImageUtils;;

public class FacedetectNode extends AbstractMediaNode<DetectionPayload, FacedetectNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FacedetectNode.class);

	public static final String OUTPUT_FACE_COUNT = "face_count";
	public static final String OUTPUT_FACEDETECT_FLAG = "facedetect_flag";

	private static final int WINDOW_COUNT = 50;

	private InspireFacedetector inspireface;
	private VideoFaceScanner videoScanner;

	@Inject
	public FacedetectNode(@Nullable LoomClient client, CortexOptions cortexOption, FacedetectNodeOptions options, InspireFacedetector inspireface, VideoFaceScanner videoScanner) {
		super(client, cortexOption, options);
		this.inspireface = inspireface;
		this.videoScanner = videoScanner;
	}

	@Override
	public void initialize() {
		Video4j.init();
	}

	@Override
	public String name() {
		return "facedetect";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		return media.isVideo() || media.isImage();
	}

	@Override
	protected NodeResult<DetectionPayload> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		if (media.isVideo()) {
			return processVideo(ctx);
		} else if (media.isImage()) {
			return processImage(ctx);
		} else {
			return ctx.skipped("No visual media").next();
		}
	}

	private NodeResult<DetectionPayload> processImage(NodeContext<LoomMedia> ctx) throws IOException {
		LoomMedia media = ctx.media();
		BufferedImage image = ImageIO.read(media.file());
		List<? extends Face> faces = inspireface.detectFaces(image);

		int count = faces != null ? faces.size() : 0;
		ctx.output(OUTPUT_FACE_COUNT, count);
		ctx.output(OUTPUT_FACEDETECT_FLAG, count > 0 ? "SUCCESS" : "NONE");

		List<Detection> detections = new ArrayList<>();
		if (faces != null) {
			for (Face face : faces) {
				FaceBox box = face.box();
				detections.add(new Detection(
					new BoundingBox(box.getStartX(), box.getStartY(), box.getWidth(), box.getHeight()),
					0, 1.0f, "face"));
			}
		}
		return ctx.origin(COMPUTED).next(DetectionPayload.of(detections));
	}

	private NodeResult<DetectionPayload> processVideo(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();

		try (VideoFile video = Videos.open(media.absolutePath())) {
			VideoFaceScannerReport report = videoScanner.scan(video, WINDOW_COUNT);
			List<VideoFace> faces = report.getFaces();
			int count = faces.size();
			ctx.output(OUTPUT_FACE_COUNT, count);
			ctx.output(OUTPUT_FACEDETECT_FLAG, "SUCCESS");

			List<Detection> detections = new ArrayList<>();
			for (VideoFace vf : faces) {
				FaceBox box = vf.box();
				int frameIndex = vf.getFrame() != null ? vf.getFrame().intValue() : 0;
				detections.add(new Detection(
					new BoundingBox(box.getStartX(), box.getStartY(), box.getWidth(), box.getHeight()),
					frameIndex, 1.0f, "face"));
			}
			return ctx.origin(COMPUTED).next(DetectionPayload.of(detections));
		} catch (InterruptedException | IOException | URISyntaxException e) {
			log.error("Failed to process video", e);
			return ctx.failure(e.getMessage()).next();
		}
	}

}
