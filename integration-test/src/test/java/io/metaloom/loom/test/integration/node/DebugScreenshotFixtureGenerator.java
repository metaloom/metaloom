package io.metaloom.loom.test.integration.node;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.facedetect.FacedetectNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.node.thumbnail.ThumbnailNode;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeOptions;
import io.metaloom.loom.pipeline.model.NodePreview;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Produces the fixtures the documentation screenshots are built from, by running the real nodes.
 *
 * <h2>Why this exists</h2>
 *
 * <p>
 * {@code loom-ui/scripts/capture-debug-screenshots.mjs} drives the real editor against an intercepted
 * API, because a halted run is a transient state and photographing one live would be a race. That
 * makes the screenshots reproducible, and it also makes them <em>fiction</em> unless something keeps
 * the fixtures honest. The first version was caught being wrong in three separate ways: it showed the
 * thumbnail node emitting {@code PROCESSED} (the node emits {@code DONE}), applied that node to a JPEG
 * (it is a video contact-sheet generator), and painted detection boxes into a hand-drawn gradient —
 * advertising an overlay the UI did not have.
 * </p>
 *
 * <p>
 * So the fixtures are not written by hand. This generator runs {@link ThumbnailNode} and
 * {@link FacedetectNode} over real footage with previews switched on and writes out exactly what they
 * produced: the contact sheet, the frame, one crop per detected face, and the encoded detection
 * elements. Whatever the screenshots then show is what the nodes actually do.
 * </p>
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * mvn -o -pl integration-test test -Dtest=DebugScreenshotFixtureGenerator -Dloom.regenerateDebugFixtures=true
 * </pre>
 *
 * <p>
 * Skipped without that flag, and skipped when the source clip is absent. Both are deliberate: this
 * needs the InspireFace and Video4j native runtimes, and it reads from {@code /opt/metaloom/loom-testdata},
 * which is outside the repository and not versioned. The <em>output</em> is committed, so nobody
 * needs any of that to build the site — only to change the pictures.
 * </p>
 */
public class DebugScreenshotFixtureGenerator {

	/** Set {@code -Dloom.regenerateDebugFixtures=true} to run. */
	private static final String REGENERATE = "loom.regenerateDebugFixtures";

	/**
	 * Two people in an office, one near-frontal and one in profile.
	 *
	 * <p>
	 * Chosen over the other clips in the test corpus because the profile face is the interesting case:
	 * it is the one where a reader can see for themselves that detection found a face the box only
	 * partly agrees with.
	 * </p>
	 */
	private static final Path SOURCE = Path.of("/opt/metaloom/loom-testdata/folderA/folderB/pexels-jack-sparrow-5977460.mp4");

	private static final Path OUT = Path.of("..", "loom-ui", "scripts", "fixtures");

	/** The InspireFace model pack, which lives in the facedetect node's own module. */
	private static final Path PACK = Path.of("..", "cortex", "nodes", "facedetect", "core", "packs", "Pikachu");

	/**
	 * The angle an operator has to reach for on this clip.
	 *
	 * <p>
	 * Both faces sit between 40 and 81 degrees of yaw, because the two people are talking to each other rather than to the camera. At the default of 30
	 * the node reports nothing at all, at full detection confidence.
	 * </p>
	 */
	private static final float WIDE_MAX_FACE_ANGLE = 90f;

	@Test
	public void testGenerateFixtures() throws Exception {
		Assumptions.assumeTrue(Boolean.getBoolean(REGENERATE),
			"Set -D" + REGENERATE + "=true to regenerate the debug screenshot fixtures");
		Assumptions.assumeTrue(Files.isReadable(SOURCE), SOURCE + " is not readable — loom-testdata is not versioned");
		Assumptions.assumeTrue(Files.isReadable(PACK), PACK + " is not readable — the InspireFace model pack is missing");

		Files.createDirectories(OUT);
		JsonObject manifest = new JsonObject()
			.put("source", SOURCE.getFileName().toString())
			.put("generatedBy", getClass().getName());

		manifest.put("thumbnail", generateThumbnail());
		// Twice, at the two settings the re-execution screenshots contrast. The default rejects both
		// faces in this clip outright, which is not a contrived example - it is what an operator
		// actually sees, and the reason being able to change a setting on a halted node is worth
		// anything at all.
		manifest.put("facedetectDefault", generateDetections(InspireFacedetector.DEFAULT_MAX_FACE_ANGLE, "default"));
		manifest.put("facedetectWide", generateDetections(WIDE_MAX_FACE_ANGLE, "wide"));

		write("manifest.json", manifest.encodePrettily().getBytes());
		System.out.println("Wrote debug screenshot fixtures to " + OUT.toAbsolutePath().normalize());
	}

	/**
	 * Run the real thumbnail node and keep its contact sheet.
	 *
	 * <p>
	 * The node writes a grid of {@code cols}×{@code rows} tiles to its meta path and emits the path on
	 * an {@code artifact/image} port. That path is what the screenshots' `thumbnail` port shows, and
	 * the bytes behind it are what the automatic preview channel would have downsampled — so copying
	 * the file out is enough; nothing has to imitate the grid.
	 * </p>
	 */
	private JsonObject generateThumbnail() throws Exception {
		Path metaPath = Files.createTempDirectory("debug-fixture-thumb");
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		ThumbnailNode node = new ThumbnailNode(null, new CortexOptions().setMetaPath(metaPath), options);
		node.initialize();

		LoomMedia media = new LoomMediaImpl(SOURCE);
		NodeResult result = node.process(media, NodeInputs.builder().build());
		String flag = String.valueOf(value(result, ThumbnailNode.OUT_FLAG.id()));
		Path produced = Path.of(String.valueOf(value(result, ThumbnailNode.OUT_THUMBNAIL.id())));
		assertTrue(Files.isReadable(produced), "the thumbnail node must have written " + produced);

		write("thumbnail-grid.jpg", Files.readAllBytes(produced));
		return new JsonObject()
			.put("flag", flag)
			.put("path", produced.toString())
			.put("cols", options.getCols())
			.put("rows", options.getRows())
			.put("tileSize", options.getTileSize());
	}

	/**
	 * Run the real face detection node with previews on, and keep everything it attached.
	 *
	 * <p>
	 * The previews come back keyed exactly as they travel to Loom — {@code detections} for the frame,
	 * {@code detections#N} for the crop of face N — so the capture script can key its fixture the same
	 * way without a translation step that could quietly diverge.
	 * </p>
	 */
	private JsonObject generateDetections(float maxFaceAngle, String label) throws Exception {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		// The pack ships inside the facedetect module, so the path is relative to *that* module rather
		// than to whoever is running. Resolved here because this generator runs from integration-test.
		options.setInspirefacePackPath(PACK.toString());
		options.setMinFaceHeightFactor(0.05f).setVideoScaleSize(512).setMaxFaceAngle(maxFaceAngle);
		InspireFacedetector detector = FacedetectNodeModule.inspirefaceDetector(options);
		FacedetectNode node = new FacedetectNode(null, new CortexOptions().setMetaPath(Files.createTempDirectory("debug-fixture-faces")),
			options, detector, new VideoFaceScanner(detector));
		node.initialize();

		LoomMedia media = new LoomMediaImpl(SOURCE);
		// capturePreviews is the whole point: without it the node does its detection work and attaches
		// nothing but the Markdown table.
		NodeResult result = node.process(media, new NodeInputs(Map.of(), java.util.Set.of(), null, null, true));

		List<String> elements = elements(result, FacedetectNode.OUT_DETECTIONS.id());
		JsonArray detections = new JsonArray();
		elements.forEach(element -> detections.add(new JsonObject(element)));

		List<String> images = new ArrayList<>();
		for (Map.Entry<String, NodePreview> entry : result.getPreviews().entrySet()) {
			NodePreview preview = entry.getValue();
			if (preview.getData() == null) {
				continue;
			}
			String name = label + "-" + entry.getKey().replace('#', '-') + ".jpg";
			write(name, preview.getData());
			images.add(name);
		}

		return new JsonObject()
			.put("maxFaceAngle", maxFaceAngle)
			.put("flag", String.valueOf(value(result, FacedetectNode.OUT_FLAG.id())))
			.put("faceCount", value(result, FacedetectNode.OUT_FACE_COUNT.id()))
			.put("elements", detections)
			.put("markdown", markdownOf(result.getPreviews()))
			.put("images", new JsonArray(images));
	}

	private static String markdownOf(Map<String, NodePreview> previews) {
		NodePreview port = previews.get(FacedetectNode.OUT_DETECTIONS.id());
		return port == null ? null : port.getMarkdown();
	}

	private static Object value(NodeResult result, String portId) {
		PortOutput output = result.getOutputs().get(portId);
		return output == null ? null : output.single();
	}

	private static List<String> elements(NodeResult result, String portId) {
		PortOutput output = result.getOutputs().get(portId);
		if (output == null) {
			return List.of();
		}
		return output.values().stream().map(String::valueOf).toList();
	}

	private void write(String name, byte[] bytes) throws IOException {
		Path target = OUT.resolve(name);
		Files.write(target, bytes);
		System.out.println("  " + target.normalize() + " (" + bytes.length + " bytes)");
	}

}
