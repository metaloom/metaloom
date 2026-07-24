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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.payload.BoundingBox;
import io.metaloom.cortex.api.node.payload.Detection;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.node.facedetect.video.VideoFace;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScannerReport;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;;

public class FacedetectNode extends AbstractMediaNode<FacedetectNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(FacedetectNode.class);

	public static final NodeOutputKey<Integer> OUTPUT_FACE_COUNT = NodeOutputKey.of("face_count", Integer.class);
	public static final NodeOutputKey<String> OUTPUT_FACEDETECT_FLAG = NodeOutputKey.of("facedetect_flag", String.class);

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
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		if (media.isVideo()) {
			return processVideo(ctx, asset);
		} else if (media.isImage()) {
			return processImage(ctx, asset);
		} else {
			return ctx.skipped("No visual media").next();
		}
	}

	private NodeResult processImage(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
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
		persist(ctx, asset, detections);
		return ctx.origin(COMPUTED).next();
	}

	private NodeResult processVideo(NodeContext<LoomMedia> ctx, AssetResponse asset) {
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
			persist(ctx, asset, detections);
			return ctx.origin(COMPUTED).next();
		} catch (InterruptedException | IOException | URISyntaxException e) {
			log.error("Failed to process video", e);
			return ctx.failure(e.getMessage()).next();
		}
	}

	/**
	 * Persist detected faces as the asset's {@code facedetect} detection set and record a ledger entry. Each face becomes a detection row keyed by
	 * (asset, node_kind, frame_number, detection_index), so re-running the node upserts rather than appends. Best-effort and a no-op when the asset is
	 * not yet known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, List<Detection> detections) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			List<DetectionCreateRequest> items = new ArrayList<>();
			int index = 0;
			for (Detection detection : detections) {
				BoundingBox box = detection.boundingBox();
				items.add(new DetectionCreateRequest()
					.setType("face")
					.setNodeKind(name())
					.setDetectionIndex(index++)
					.setFrameNumber(detection.frameIndex())
					.setBboxX((float) box.x())
					.setBboxY((float) box.y())
					.setBboxWidth((float) box.width())
					.setBboxHeight((float) box.height())
					.setConfidence(detection.confidence()));
			}
			client().bulkCreateAssetDetections(asset.getUuid(), new DetectionBulkCreateRequest().setDetections(items)).sync();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, resultRef("detection"));
		} catch (Exception e) {
			log.warn("Failed to persist detections for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

}
