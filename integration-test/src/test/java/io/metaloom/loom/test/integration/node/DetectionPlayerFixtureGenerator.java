package io.metaloom.loom.test.integration.node;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.cortex.node.facedetect.FacedetectNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.loom.pipeline.model.NodePreview;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Produces the detection track the documentation's video player plays.
 *
 * <h2>What this is for</h2>
 *
 * <p>
 * The Debug Mode page shows the detections of a video drawn over a single still, and readers
 * reasonably ask why several boxes land on the same face. The answer is that a detector reports one
 * detection <em>per sampled frame</em>, so the boxes belong to different moments — which a still
 * cannot show and a video can. {@code /docs/pipeline/} therefore plays the clip with the boxes
 * painted in at the moment each one was found.
 * </p>
 *
 * <p>
 * That player is only worth having if the boxes are the real ones, so this generator runs the real
 * {@link FacedetectNode} over the real clip and writes out exactly the elements it emitted. Nothing
 * here invents, smooths, interpolates or pads the track. In particular the <strong>sparseness is
 * real</strong>: the video path keeps only the ten sharpest faces it found across the whole scan, so
 * a thirteen-second clip yields ten detections at a handful of frames rather than a box on every
 * frame. That is the behaviour the page is explaining, and a denser hand-made track would explain
 * something the product does not do.
 * </p>
 *
 * <h2>Shape of the output</h2>
 *
 * <p>
 * {@code detections} is the node's own encoded elements, parsed and otherwise untouched — the same
 * documents that travel downstream, complete with {@code coordinates}, {@code imageWidth} and
 * {@code imageHeight}. The player divides by those exactly as the product's own overlay does, rather
 * than being handed pre-normalised numbers that could quietly disagree with it.
 * </p>
 *
 * <p>
 * The face crops are packed into one horizontal sprite strip instead of a file each: a page that
 * fetched ten separate thumbnails would spend ten requests on the smallest thing on it, and the tile
 * count is bounded by the node's own cap.
 * </p>
 *
 * <p>
 * <strong>Nothing here is node-specific beyond the class it constructs.</strong> Any node emitting a
 * {@code detection/*} port emits the same element shape, so an object detector's track is this same
 * file with a different {@code node} and different labels — the player reads labels off the elements
 * and does not know what a face is.
 * </p>
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * mvn -o -pl integration-test test -Dtest=DetectionPlayerFixtureGenerator -Dloom.regenerateDetectionTrack=true
 * </pre>
 *
 * <p>
 * The companion {@code .mp4} is not produced here — it is a plain re-encode of the same source, made
 * with the command recorded in the JSON under {@code video.encodedWith} so it can be reproduced.
 * </p>
 */
public class DetectionPlayerFixtureGenerator {

	/** Set {@code -Dloom.regenerateDetectionTrack=true} to run. */
	private static final String REGENERATE = "loom.regenerateDetectionTrack";

	/** The same meeting clip the debug screenshots use, so the page tells one story throughout. It is
	 * the demo container's own footage: a reader who downloads the image can find this file in it. */
	private static final Path SOURCE = Path.of("/opt/metaloom/loom-testdata/folderA/folderB/pexels-jack-sparrow-5977460.mp4");

	/** The page bundle of {@code /docs/pipeline/}: co-located resources, like every other figure there. */
	private static final Path OUT = Path.of("..", "website", "content", "english", "docs", "pipeline");

	private static final Path PACK = Path.of("..", "cortex", "nodes", "facedetect", "core", "packs", "Pikachu");

	/**
	 * Both people in this clip are talking to each other rather than to the camera, so both faces sit
	 * well past the default 30° yaw gate and the node reports nothing at all at full confidence. The
	 * debug screenshots contrast exactly that; the player needs the setting that finds them.
	 */
	private static final float MAX_FACE_ANGLE = 90f;

	/** Edge of one sprite tile. Twice the largest size the strip is displayed at, for HiDPI. */
	private static final int TILE_PX = 128;

	/** Seconds of clip kept before the first detection and after the last — see {@link #videoInfo}. */
	private static final double LEAD_IN_SECONDS = 1.6;
	private static final double TAIL_SECONDS = 1.2;

	private static final String TRACK = "facedetect-detections.json";
	private static final String SPRITE = "facedetect-faces.jpg";
	private static final String POSTER = "facedetect-demo-poster.jpg";

	@Test
	public void testGenerateDetectionTrack() throws Exception {
		Assumptions.assumeTrue(Boolean.getBoolean(REGENERATE),
			"Set -D" + REGENERATE + "=true to regenerate the documentation detection track");
		Assumptions.assumeTrue(Files.isReadable(SOURCE), SOURCE + " is not readable — loom-testdata is not versioned");
		Assumptions.assumeTrue(Files.isReadable(PACK), PACK + " is not readable — the InspireFace model pack is missing");

		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setInspirefacePackPath(PACK.toString());
		options.setMinFaceHeightFactor(0.05f).setVideoScaleSize(512).setMaxFaceAngle(MAX_FACE_ANGLE);

		InspireFacedetector detector = FacedetectNodeModule.inspirefaceDetector(options);
		FacedetectNode node = new FacedetectNode(null,
			new CortexOptions().setMetaPath(Files.createTempDirectory("detection-track")),
			options, detector, new VideoFaceScanner(detector));
		node.initialize();

		LoomMedia media = new LoomMediaImpl(SOURCE);
		// capturePreviews is what makes the node cut the crops. Without it there is a track but no
		// pictures to put beside it.
		NodeResult result = node.process(media, new NodeInputs(Map.of(), Set.of(), null, null, true));

		JsonArray detections = new JsonArray();
		elements(result, FacedetectNode.OUT_DETECTIONS.id())
			.forEach(element -> detections.add(new JsonObject(element)));
		assertFalse(detections.isEmpty(), "the clip must yield detections, or there is no track to play");

		JsonObject track = new JsonObject()
			.put("generatedBy", getClass().getName())
			.put("source", SOURCE.getFileName().toString())
			.put("node", "facedetect")
			.put("options", new JsonObject()
				.put("videoChopRate", options.getVideoChopRate())
				.put("videoScaleSize", options.getVideoScaleSize())
				.put("minFaceHeightFactor", options.getMinFaceHeightFactor())
				.put("maxFaceAngle", options.getMaxFaceAngle()))
			.put("video", videoInfo(detections))
			.put("sprite", writeSprite(result, detections.size()))
			.put("detections", detections);

		writePoster(result);
		write(TRACK, track.encodePrettily().getBytes());
		System.out.println("Wrote " + detections.size() + " detections to " + OUT.resolve(TRACK).normalize());
	}

	/**
	 * The clip's own geometry and timing, plus the window the demo file is cut from.
	 *
	 * <p>
	 * The frame rate is what turns a detection's frame index into a moment in the player, so it is
	 * read off the file rather than assumed — a track built against the wrong rate drifts further out
	 * of step the longer the clip runs, which looks exactly like a detector that cannot follow a face.
	 * </p>
	 *
	 * <p>
	 * <strong>{@code frameOffset} is not cosmetic.</strong> The detections carry frame indices in the
	 * <em>source's</em> numbering, and the demo file is a cut of it, so the player has to add the
	 * offset back or every box appears seconds early. The window is derived from the detections
	 * themselves rather than hardcoded: it opens shortly before the first one and closes shortly after
	 * the last, so re-running this after a settings change moves the cut with the results instead of
	 * quietly trimming half of them away.
	 * </p>
	 */
	private JsonObject videoInfo(JsonArray detections) {
		Video4j.init();
		try (VideoFile video = Videos.open(SOURCE.toString())) {
			double fps = video.fps();
			long total = video.length();
			int first = Integer.MAX_VALUE, last = Integer.MIN_VALUE;
			for (int i = 0; i < detections.size(); i++) {
				int frame = detections.getJsonObject(i).getInteger("frame", 0);
				first = Math.min(first, frame);
				last = Math.max(last, frame);
			}
			// Enough lead-in to see the shot before anything is found — the empty stretch is part of
			// the point, because it is where the detector looked and reported nothing.
			long start = Math.max(0, first - Math.round(fps * LEAD_IN_SECONDS));
			long end = Math.min(total, last + Math.round(fps * TAIL_SECONDS) + 1);

			return new JsonObject()
				.put("file", "facedetect-demo.mp4")
				.put("width", video.width())
				.put("height", video.height())
				.put("fps", fps)
				.put("frames", total)
				.put("frameOffset", start)
				.put("frameEnd", end)
				// Recorded so the companion file can be rebuilt from the same source. The demo is a
				// downscale of the original 4K, which is 36 MB and has no business on a docs page; the
				// detections stay in the source's own pixel space and are normalised at draw time.
				//
				// `trim` by frame number rather than `-ss` by timestamp: the offset the player adds
				// back is a frame count, and a seek that lands on a neighbouring frame would put every
				// box one sample out for the whole clip.
				.put("encodedWith", "ffmpeg -i " + SOURCE.getFileName()
					+ " -an -vf \"trim=start_frame=" + start + ":end_frame=" + end
					+ ",setpts=PTS-STARTPTS,scale=1280:-2\""
					+ " -c:v libx264 -crf 30 -preset slow -movflags +faststart facedetect-demo.mp4");
		}
	}

	/**
	 * Pack the per-detection crops into one horizontal strip, in element order.
	 *
	 * <p>
	 * Tile <em>n</em> is detection <em>n</em>, so the player needs no index: it offsets by the
	 * element's own position. A detection whose crop could not be cut leaves its tile empty rather
	 * than shifting every later one, which would silently mislabel the whole strip.
	 * </p>
	 */
	private JsonObject writeSprite(NodeResult result, int count) throws IOException {
		BufferedImage strip = new BufferedImage(TILE_PX * count, TILE_PX, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = strip.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setColor(new Color(0x14, 0x18, 0x1d));
		g.fillRect(0, 0, strip.getWidth(), strip.getHeight());

		int drawn = 0;
		for (int i = 0; i < count; i++) {
			NodePreview crop = result.getPreviews().get(FacedetectNode.OUT_DETECTIONS.id() + "#" + i);
			if (crop == null || crop.getData() == null) {
				continue;
			}
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(crop.getData()));
			if (img == null) {
				continue;
			}
			g.drawImage(img, i * TILE_PX, 0, TILE_PX, TILE_PX, null);
			drawn++;
		}
		g.dispose();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(strip, "jpg", out);
		write(SPRITE, out.toByteArray());
		return new JsonObject().put("file", SPRITE).put("tile", TILE_PX).put("count", count).put("filled", drawn);
	}

	/**
	 * The poster is the node's own port-level preview: the frame it measured the boxes against.
	 *
	 * <p>
	 * Which means the picture the player shows before anyone presses play is the same picture the
	 * debugging view shows, and the page can say so.
	 * </p>
	 */
	private void writePoster(NodeResult result) throws IOException {
		NodePreview frame = result.getPreviews().get(FacedetectNode.OUT_DETECTIONS.id());
		assertNotNull(frame, "the detections port must carry the frame the boxes were measured on");
		assertNotNull(frame.getData(), "the port preview must carry bytes");
		write(POSTER, frame.getData());
	}

	private static List<String> elements(NodeResult result, String portId) {
		PortOutput output = result.getOutputs().get(portId);
		return output == null ? new ArrayList<>() : output.values().stream().map(String::valueOf).toList();
	}

	private void write(String name, byte[] bytes) throws IOException {
		Files.createDirectories(OUT);
		Path target = OUT.resolve(name);
		Files.write(target, bytes);
		System.out.println("  " + target.normalize() + " (" + bytes.length + " bytes)");
	}
}
