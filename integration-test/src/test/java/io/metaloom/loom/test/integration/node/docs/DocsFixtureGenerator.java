package io.metaloom.loom.test.integration.node.docs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.inject.Provider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.color.DominantColorNode;
import io.metaloom.cortex.node.color.DominantColorNodeOptions;
import io.metaloom.cortex.node.consistency.ConsistencyNode;
import io.metaloom.cortex.node.consistency.ConsistencyNodeOptions;
import io.metaloom.cortex.node.facedetect.FacedetectNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.node.fp.FingerprintNode;
import io.metaloom.cortex.node.fp.FingerprintNodeOptions;
import io.metaloom.cortex.node.hash.HashNodeOptions;
import io.metaloom.cortex.node.hash.SHA512Node;
import io.metaloom.cortex.node.imagemanip.ImageManipulationNode;
import io.metaloom.cortex.node.imagemanip.ImageManipulationNodeOptions;
import io.metaloom.cortex.node.objectdetect.ObjectDetectNode;
import io.metaloom.cortex.node.objectdetect.ObjectDetectNodeOptions;
import io.metaloom.cortex.node.objectdetect.ObjectDetector;
import io.metaloom.cortex.node.objectdetect.YoloObjectDetector;
import io.metaloom.cortex.node.objectdetect.video.VideoObjectScanner;
import io.metaloom.cortex.node.ocr.OCRNode;
import io.metaloom.cortex.node.ocr.OCRNodeModule;
import io.metaloom.cortex.node.ocr.OCRNodeOptions;
import io.metaloom.cortex.node.script.ScriptNode;
import io.metaloom.cortex.node.script.ScriptNodeOptions;
import io.metaloom.cortex.node.script.engine.js.GraalJsScriptEngine;
import io.metaloom.cortex.node.tag.LabelsTagStrategy;
import io.metaloom.cortex.node.tag.RulesTagStrategy;
import io.metaloom.cortex.node.tag.TagBy;
import io.metaloom.cortex.node.tag.TagNode;
import io.metaloom.cortex.node.tag.TagNodeOptions;
import io.metaloom.cortex.node.tag.TagStrategy;
import io.metaloom.cortex.node.watermark.WatermarkNode;
import io.metaloom.cortex.node.watermark.WatermarkNodeOptions;
import io.metaloom.cortex.node.metadata.MetadataNode;
import io.metaloom.cortex.node.metadata.MetadataNodeOptions;
import io.metaloom.cortex.node.quality.QualityNode;
import io.metaloom.cortex.node.quality.QualityNodeOptions;
import io.metaloom.cortex.node.scene.SceneDetectionNode;
import io.metaloom.cortex.node.scene.SceneDetectionOptions;
import io.metaloom.cortex.node.thumbnail.ThumbnailNode;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeOptions;
import io.metaloom.cortex.node.tika.TikaNode;
import io.metaloom.cortex.node.tika.TikaNodeOptions;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Outcome;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Requirement;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Upstream;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.Video4j;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Runs each node for real and writes the fixtures the node pages' debug screenshots are built from.
 *
 * <h2>Why the screenshots need this at all</h2>
 *
 * <p>
 * {@code loom-ui/scripts/capture-node-screenshots.mjs} drives the real editor against a fully
 * intercepted API — no server, no database, no worker — because that is the only way to photograph
 * thirty-odd nodes reproducibly. The cost of that is that every payload on screen is something a
 * script decided to say. This generator is what stops those payloads being fiction: each one is the
 * output of the actual node, run over actual media, mapped through the actual wire mapper.
 * </p>
 *
 * <p>
 * The previous generation of this idea got caught inventing three separate things — a state the node
 * does not emit, a media type it does not accept, and an overlay the UI did not have. Nothing here
 * is written by hand.
 * </p>
 *
 * <h2>Partial by design</h2>
 *
 * <p>
 * Roughly half the catalogue needs something this machine may not have running: a language model on
 * 8080, a depth sidecar on 9120, MinIO, a Whisper model. A recipe whose {@link Requirement} is not
 * satisfied <strong>aborts with the command that would satisfy it</strong> and leaves the committed
 * fixture untouched, so a partial run is normal and never destructive. Under
 * {@code -Dloom.docsFixturesStrict=true} an unsatisfied requirement fails instead, which is what a
 * release build wants: it is the difference between "we did not take that picture today" and "we
 * silently stopped taking it months ago".
 * </p>
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * mvn -o -pl integration-test test -Dtest=DocsFixtureGenerator -Dloom.regenerateDocsFixtures=true
 * mvn -o -pl integration-test test -Dtest=DocsFixtureGenerator -Dloom.regenerateDocsFixtures=true -Dloom.docsFixtureKinds=tika,sha512
 * </pre>
 */
public class DocsFixtureGenerator {

	private static final String REGENERATE = "loom.regenerateDocsFixtures";
	private static final String STRICT = "loom.docsFixturesStrict";
	private static final String KINDS = "loom.docsFixtureKinds";

	private static final Path OUT = Path.of("..", "loom-ui", "scripts", "fixtures", "nodes");
	private static final Path PACK = Path.of("..", "cortex", "nodes", "facedetect", "core", "packs", "Pikachu");

	/**
	 * Where the Whisper weights are, overridable because they are not in the repository.
	 *
	 * <p>
	 * 1.6 GB of model, downloaded by {@code whisper.cpp/models/download-ggml-model.sh}. The default
	 * is where that script puts it on a checkout beside this one; a machine that keeps it elsewhere
	 * passes {@code -Dloom.docsWhisperModel=/path/to/ggml-....bin}.
	 * </p>
	 */
	private static final Path WHISPER_MODEL = Path.of(System.getProperty("loom.docsWhisperModel",
		System.getProperty("user.home") + "/workspaces/metaloom/whisper.cpp/models/ggml-large-v3-turbo.bin"));

	/**
	 * The YOLO weights and class names, likewise outside the repository.
	 *
	 * <p>
	 * The node's own default ({@code models/yolo/YOLOv11n_voc.onnx}) is relative to the worker's
	 * working directory and resolves to nothing from a test. The default here is where yolo4j keeps
	 * the same file — that sibling checkout is also where the native library comes from, so a machine
	 * that can run this node at all has it.
	 * </p>
	 */
	private static final Path YOLO_DIR = Path.of(System.getProperty("user.home"),
		"workspaces/metaloom/yolo4j/YOLOs-CPP-1.0.0/models");

	private static final Path YOLO_MODEL = Path.of(System.getProperty("loom.docsYoloModel",
		YOLO_DIR.resolve("YOLOv11n_voc.onnx").toString()));

	private static final Path YOLO_LABELS = Path.of(System.getProperty("loom.docsYoloLabels",
		YOLO_DIR.resolve("voc.names").toString()));

	@TestFactory
	public Stream<DynamicTest> generateDocsFixtures() throws Exception {
		Assumptions.assumeTrue(Boolean.getBoolean(REGENERATE),
			"Set -D" + REGENERATE + "=true to regenerate the node documentation fixtures");

		Files.createDirectories(OUT);
		FixtureEnv env = new FixtureEnv();
		DocsFixtureWriter writer = new DocsFixtureWriter(OUT);
		String only = System.getProperty(KINDS, "");
		List<String> wanted = only.isBlank() ? List.of() : List.of(only.split(","));

		return recipes().stream()
			.filter(r -> wanted.isEmpty() || wanted.contains(r.kind()))
			.map(recipe -> DynamicTest.dynamicTest(recipe.kind(), () -> {
				Requirement need = recipe.requirement();
				if (!need.satisfied()) {
					String message = recipe.kind() + ": " + need.describe() + " is not available — " + need.hint();
					if (Boolean.getBoolean(STRICT)) {
						throw new IllegalStateException(message);
					}
					Assumptions.abort(message);
				}
				Outcome outcome = recipe.run(env);
				writer.write(recipe, outcome.result(), outcome.mediaPath(), outcome.nodeData());
			}));
	}

	// ------------------------------------------------------------------------
	// Recipes
	// ------------------------------------------------------------------------

	/**
	 * Every node is constructed with a {@code null} Loom client on purpose — see {@link FixtureEnv}.
	 * The node options are the shipped defaults wherever the defaults produce something worth
	 * looking at; where they do not, the deviation is explained on the spot, because a screenshot
	 * taken at a non-default setting has to say so.
	 */
	private static List<DocsFixtureRecipe> recipes() {
		List<DocsFixtureRecipe> recipes = new ArrayList<>();

		recipes.add(simple("sha512", Requirement.offline(), env -> {
			var media = env.image1();
			SHA512Node node = new SHA512Node(null, env.cortexOptions("sha512"), new HashNodeOptions());
			node.initialize();
			return new Outcome(node.process(NodeContext.create(env.media(media))), env.displayPath(media));
		}));

		recipes.add(simple("consistency", Requirement.offline(), env -> {
			var media = env.video1();
			ConsistencyNode node = new ConsistencyNode(null, env.cortexOptions("consistency"), new ConsistencyNodeOptions());
			node.initialize();
			return new Outcome(node.process(NodeContext.create(env.media(media))), env.displayPath(media));
		}));

		recipes.add(simple("tika", Requirement.offline(), env -> {
			// A DOCX rather than a plain text file: the point of this node is that it opens formats
			// nothing else on the page can, and a .txt would make it look like a file reader.
			var media = env.docDOCX();
			TikaNode node = new TikaNode(null, env.cortexOptions("tika"), new TikaNodeOptions());
			node.initialize();
			return new Outcome(node.process(NodeContext.create(env.media(media))), env.displayPath(media));
		}));

		recipes.add(simple("metadata", Requirement.offline(), env -> {
			var media = env.image1();
			MetadataNode node = new MetadataNode(null, env.cortexOptions("metadata"), new MetadataNodeOptions());
			node.initialize();
			return new Outcome(node.process(NodeContext.create(env.media(media))), env.displayPath(media));
		}));

		recipes.add(simple("image-manipulation", Requirement.offline(), env -> {
			var media = env.image1();
			ImageManipulationNodeOptions options = new ImageManipulationNodeOptions();
			CortexOptions cortex = env.cortexOptions("image-manipulation");
			ImageManipulationNode node = new ImageManipulationNode(null, cortex, options);
			node.initialize();
			return new Outcome(node.process(context(env.media(media), true)), env.displayPath(media));
		}));

		recipes.add(video("thumbnail", env -> {
			var media = env.video1();
			ThumbnailNodeOptions options = new ThumbnailNodeOptions();
			ThumbnailNode node = new ThumbnailNode(null, env.cortexOptions("thumbnail"), options);
			node.initialize();
			return new Outcome(node.process(context(env.media(media), true)), env.displayPath(media));
		}));

		recipes.add(video("fingerprint", env -> {
			var media = env.video1();
			FingerprintNode node = new FingerprintNode(null, env.cortexOptions("fingerprint"), new FingerprintNodeOptions());
			node.initialize();
			return new Outcome(node.process(NodeContext.create(env.media(media))), env.displayPath(media));
		}));

		recipes.add(video("quality", env -> {
			var media = env.video1();
			QualityNode node = new QualityNode(null, env.cortexOptions("quality"), new QualityNodeOptions());
			node.initialize();
			return new Outcome(node.process(NodeContext.create(env.media(media))), env.displayPath(media));
		}));

		recipes.add(video("scene-detection", env -> {
			var media = env.video1();
			SceneDetectionNode node = new SceneDetectionNode(null, env.cortexOptions("scene-detection"), new SceneDetectionOptions());
			node.initialize();
			return new Outcome(node.process(NodeContext.create(env.media(media))), env.displayPath(media));
		}));

		recipes.add(new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "facedetect";
			}

			@Override
			public Requirement requirement() {
				return Requirement.file(PACK, "the InspireFace model pack ships in cortex/nodes/facedetect/core/packs");
			}

			@Override
			public List<Upstream> upstream() {
				// The `video` branch, not `image`: the two paths behave differently enough that
				// drawing the wrong one would misdescribe the node.
				return List.of(new Upstream("filesystem-source", "media", "video"));
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				var media = env.video1();
				FacedetectNodeOptions options = new FacedetectNodeOptions();
				options.setInspirefacePackPath(PACK.toString());
				FacedetectNode node = new FacedetectNode(null, env.cortexOptions("facedetect"), options,
					detector(options), scanner(options));
				node.initialize();
				return new Outcome(node.process(context(env.media(media), true)), env.displayPath(media),
					new JsonObject().put("videoChopRate", options.getVideoChopRate()));
			}
		});

		recipes.add(new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "objectdetect";
			}

			@Override
			public Requirement requirement() {
				return Requirement.file(YOLO_MODEL,
					"point -Dloom.docsYoloModel / -Dloom.docsYoloLabels at a YOLO ONNX model and its "
						+ "class names — yolo4j ships YOLOv11n_voc.onnx and voc.names under YOLOs-CPP-*/models");
			}

			@Override
			public List<Upstream> upstream() {
				// The video branch of the exclusive input group, because that is what ran.
				return List.of(new Upstream("filesystem-source", "media", "video"));
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				var media = env.video1();
				ObjectDetectNodeOptions options = new ObjectDetectNodeOptions();
				options.setModelPath(YOLO_MODEL.toString());
				options.setLabelsPath(YOLO_LABELS.toString());
				// CPU. The ONNX Runtime bundled with yolo4j is built against a different CUDA major
				// than the driver here, and asking for the GPU turns a clean answer into a provider
				// warning followed by the same answer, slower. Nothing about the detections differs.
				options.setUseGpu(false);
				ObjectDetector detector = new YoloObjectDetector(options.getModelPath(), options.getLabelsPath(),
					options.isUseGpu(), options.getOnnxRuntimeLibPath());
				ObjectDetectNode node = new ObjectDetectNode(null, env.cortexOptions("objectdetect"), options,
					detector, new VideoObjectScanner(detector));
				node.initialize();
				return new Outcome(node.process(context(env.media(media), true)), env.displayPath(media),
					new JsonObject()
						.put("modelPath", YOLO_MODEL.getFileName().toString())
						.put("videoChopRate", options.getVideoChopRate())
						.put("minConfidence", options.getMinConfidence())
						.put("useGpu", options.isUseGpu()));
			}
		});

		recipes.add(simple("dominant-color", Requirement.offline(), env -> {
			// No detections wired: the `detections` port is optional and the node then reads the
			// whole picture, which is the simpler of its two modes and the one worth showing first.
			var media = env.image1();
			DominantColorNode node = new DominantColorNode(null, env.cortexOptions("dominant-color"),
				new DominantColorNodeOptions());
			node.initialize();
			return new Outcome(node.process(NodeContext.create(env.media(media))), env.displayPath(media));
		}));

		recipes.add(simple("ocr", Requirement.file(Path.of(new OCRNodeOptions().getTessDataPath()),
			"install tesseract-ocr and its language data (the node's tessDataPath option points at it)"), env -> {
			// The corpus photograph has no text in it, so it would produce an empty and thoroughly
			// misleading result. This is the one recipe that reaches outside the copied corpus.
			Path scan = env.inLibrary(Path.of("/opt/metaloom/loom-testdata/ocr/albert_einstein.png"));
			OCRNodeOptions options = new OCRNodeOptions();
			OCRNode node = new OCRNode(null, env.cortexOptions("ocr"), options, OCRNodeModule.ocrProvider(options));
			node.initialize();
			return new Outcome(node.process(NodeContext.create(env.media(scan))), scan.toString());
		}));

		recipes.add(simple("watermark", Requirement.offline(), env -> {
			// A real mark, generated here rather than shipped: the node takes it as base64 in its
			// options, so there is nothing to check in that a file would add.
			WatermarkNodeOptions options = new WatermarkNodeOptions()
				.setWatermarkBase64(markBase64())
				.setRelX(0.72).setRelY(0.78).setScale(0.18);
			WatermarkNode node = new WatermarkNode(null, env.cortexOptions("watermark"), options);
			node.initialize();
			var media = env.image1();
			return new Outcome(node.process(context(env.media(media), true)), env.displayPath(media),
				new JsonObject().put("relX", 0.72).put("relY", 0.78).put("scale", 0.18));
		}));

		recipes.add(simple("tag", Requirement.offline(), env -> {
			// Offline, so no tag is durably written — but the node still evaluates its rules and
			// emits the verdict, which is what the card shows. The rule is a real one.
			JsonObject nodeDef = new JsonObject()
				.put("id", "tag")
				.put("collection", "docs")
				.put("rules", new JsonArray().add(new JsonObject()
					.put("id", "r-has-transcript")
					.put("tag", "has-transcript")
					.put("when", new JsonArray().add(new JsonObject().put("input", "text").put("op", "NOT_BLANK")))));
			TagNode node = new TagNode(null, env.cortexOptions("tag"), new TagNodeOptions(),
				Map.of(TagBy.RULES, (Provider<TagStrategy>) RulesTagStrategy::new,
					TagBy.LABELS, (Provider<TagStrategy>) LabelsTagStrategy::new));
			node.configure(nodeDef);
			node.initialize();
			var media = env.image1();
			NodeContext<LoomMedia> ctx = NodeContext.create(env.media(media),
				NodeInputs.builder().input(TagNode.IN_TEXT, "a narrator introduces the two speakers").build());
			return new Outcome(node.process(ctx), env.displayPath(media), nodeDef);
		}));

		recipes.add(simple("script", Requirement.offline(), env -> {
			// The script *is* the node's configuration, so the picture is worth nothing without a
			// real one. This is the chapter-marking example from the node's own tests, verbatim —
			// including the binding names, which are `data.text` / `out.*` / `params` / `log` and
			// not the obvious guesses. Writing a plausible-looking script instead is how this recipe
			// first produced a FAILED fixture reading "ReferenceError: text is not defined".
			String script = """
				const transcript = JSON.parse(data.text);
				const frames = transcript.segments
				  .filter(s => new RegExp(params.pattern, 'i').test(s.text))
				  .map(s => ({ startMs: Math.round(s.start * 1000), endMs: Math.round(s.end * 1000), label: s.text }));
				out.timeframes('chapter_frames', frames);
				out.list('chapter_titles', frames.map(f => f.label));
				out.integer('chapter_count', frames.length);
				log.info('derived ' + frames.length + ' chapter marks');
				""";
			JsonObject nodeDef = new JsonObject()
				.put("id", "script")
				.put("type", ScriptNode.KIND)
				.put("script", script)
				.put("params", new JsonObject().put("pattern", "chapter"))
				.put("outputs", new JsonArray()
					.add(new JsonObject().put("key", "chapter_frames").put("type", "TIMEFRAMES"))
					.add(new JsonObject().put("key", "chapter_titles").put("type", "TEXT_LIST"))
					.add(new JsonObject().put("key", "chapter_count").put("type", "INTEGER")));
			ScriptNode node = new ScriptNode(null, env.cortexOptions("script"), new ScriptNodeOptions(),
				Map.of(GraalJsScriptEngine.ID, GraalJsScriptEngine::new));
			node.configure(nodeDef);
			node.initialize();
			var media = env.video1();
			String transcript = new JsonObject()
				.put("segments", new JsonArray()
					.add(new JsonObject().put("start", 0.0).put("end", 1.0).put("text", "welcome"))
					.add(new JsonObject().put("start", 2.0).put("end", 3.5).put("text", "Chapter one"))
					.add(new JsonObject().put("start", 9.0).put("end", 10.25).put("text", "Chapter two")))
				.encode();
			NodeContext<LoomMedia> ctx = NodeContext.create(env.media(media), NodeInputs.builder()
				.input(ScriptNode.IN_TEXT, transcript)
				.build());
			return new Outcome(node.process(ctx), env.displayPath(media), nodeDef);
		}));

		recipes.add(SourceRecipes.filesystemSource());
		recipes.add(SourceRecipes.driveSource("gdrive-source", io.metaloom.cortex.cloud.CloudProviderId.GDRIVE));
		recipes.add(SourceRecipes.driveSource("onedrive-source", io.metaloom.cortex.cloud.CloudProviderId.ONEDRIVE));
		recipes.add(S3Recipes.s3Source());
		recipes.add(S3Recipes.s3Sink());
		recipes.add(LlmRecipes.llm());
		recipes.add(LlmRecipes.translate());
		recipes.add(LlmRecipes.guard());
		recipes.add(LlmRecipes.filter());
		recipes.add(SidecarRecipes.depthmap());
		recipes.add(SidecarRecipes.sentiment());
		recipes.add(SidecarRecipes.tts());
		recipes.add(SidecarRecipes.imagegen());
		recipes.add(SidecarRecipes.videogen());
		recipes.add(SidecarRecipes.whisper(WHISPER_MODEL));
		recipes.add(SidecarRecipes.sceneLayout(PACK));
		recipes.add(VisionRecipes.vlm());
		recipes.add(VisionRecipes.captioning());
		recipes.add(VisionRecipes.facedescription(PACK));

		return recipes;
	}

	/** A small solid mark, so the watermark node has something real to composite. */
	private static String markBase64() throws Exception {
		java.awt.image.BufferedImage mark = new java.awt.image.BufferedImage(64, 64,
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = mark.createGraphics();
		g.setColor(new java.awt.Color(0x57, 0xcb, 0xcc, 200));
		g.fillOval(4, 4, 56, 56);
		g.setColor(java.awt.Color.WHITE);
		g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 30));
		g.drawString("M", 22, 43);
		g.dispose();
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(mark, "png", out);
		return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
	}

	private static InspireFacedetector detector(FacedetectNodeOptions options) {
		return FacedetectNodeModule.inspirefaceDetector(options);
	}

	private static VideoFaceScanner scanner(FacedetectNodeOptions options) {
		return new VideoFaceScanner(detector(options));
	}

	/** A context with previews on — the only reason any of these fixtures carry pictures. */
	private static NodeContext<LoomMedia> context(LoomMedia media, boolean previews) {
		return NodeContext.create(media, new NodeInputs(java.util.Map.of(), java.util.Set.of(), null, null, previews));
	}

	@FunctionalInterface
	private interface Body {
		Outcome run(FixtureEnv env) throws Exception;
	}

	private static DocsFixtureRecipe simple(String kind, Requirement requirement, Body body) {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return kind;
			}

			@Override
			public Requirement requirement() {
				return requirement;
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				return body.run(env);
			}
		};
	}

	/**
	 * A node that decodes video, and therefore needs the OpenCV/video4j native runtime.
	 *
	 * <p>
	 * Probed by actually initialising it rather than by looking for a file: the library can be
	 * present and still fail to load against the wrong OpenCV major, and the difference is invisible
	 * from the filesystem.
	 */
	private static DocsFixtureRecipe video(String kind, Body body) {
		boolean available;
		try {
			Video4j.init();
			available = true;
		} catch (Throwable t) {
			available = false;
		}
		return simple(kind, Requirement.nativeLib("video4j/OpenCV", available,
			"install the OpenCV JNI bindings and rebuild video4j — see spec/sidecars"), body);
	}

	/** Unused today, kept so the next recipe that needs a service has the shape to copy. */
	@SuppressWarnings("unused")
	private static Requirement sidecar(String name, int port) {
		return Requirement.service(name, "http://127.0.0.1:" + port + "/health",
			"start it with sidecars/" + name + "/run.sh (create its venv with setup.sh first)");
	}

}
