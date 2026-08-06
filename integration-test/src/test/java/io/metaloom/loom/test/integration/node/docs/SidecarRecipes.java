package io.metaloom.loom.test.integration.node.docs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.depthmap.DepthmapClient;
import io.metaloom.cortex.node.depthmap.DepthmapNode;
import io.metaloom.cortex.node.depthmap.DepthmapNodeOptions;
import io.metaloom.cortex.node.facedetect.FacedetectNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.node.imagegen.ImageGenClient;
import io.metaloom.cortex.node.imagegen.ImageGenMode;
import io.metaloom.cortex.node.imagegen.ImageGenNode;
import io.metaloom.cortex.node.imagegen.ImageGenNodeOptions;
import io.metaloom.cortex.node.sam2.Sam2Client;
import io.metaloom.cortex.node.sam2.Sam2Node;
import io.metaloom.cortex.node.sam2.Sam2NodeOptions;
import io.metaloom.cortex.node.sam2.video.Sam2FrameSampler;
import io.metaloom.cortex.node.scenelayout.SceneLayoutNode;
import io.metaloom.cortex.node.scenelayout.SceneLayoutNodeOptions;
import io.metaloom.cortex.node.sentiment.SentimentClient;
import io.metaloom.cortex.node.sentiment.SentimentNode;
import io.metaloom.cortex.node.sentiment.SentimentNodeOptions;
import io.metaloom.cortex.node.tts.TtsClient;
import io.metaloom.cortex.node.tts.TtsNode;
import io.metaloom.cortex.node.tts.TtsNodeOptions;
import io.metaloom.cortex.node.videogen.VideoGenClient;
import io.metaloom.cortex.node.videogen.VideoGenMode;
import io.metaloom.cortex.node.videogen.VideoGenNode;
import io.metaloom.cortex.node.videogen.VideoGenNodeOptions;
import io.metaloom.cortex.node.whisper.WhisperMediaProcessor;
import io.metaloom.cortex.node.whisper.WhisperNode;
import io.metaloom.cortex.node.whisper.WhisperOptions;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Outcome;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Requirement;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Upstream;
import io.vertx.core.json.JsonObject;

/**
 * The nodes that are HTTP clients of a Python sidecar.
 *
 * <p>
 * Each of these is a thin Java client in front of a model server, which is exactly why the node
 * tests stub the client out — and exactly why a screenshot taken that way would be worthless. A
 * depth map is either a real estimate of a real photograph or it is a gradient somebody painted;
 * {@code DepthmapNodeIntegrationTest} paints one. The requirement below names the command that
 * starts the real server, and an unsatisfied requirement aborts rather than substituting anything.
 * </p>
 *
 * <p>
 * The sidecars live in {@code sidecars/} and each carries a {@code setup.sh} (venv + requirements)
 * and a {@code run.sh} (uvicorn on the port the node already defaults to). Nothing here overrides a
 * host or a port: if the sidecar is where the shipped options say it is, these recipes find it.
 * </p>
 */
public final class SidecarRecipes {

	private SidecarRecipes() {
	}

	private static Requirement sidecar(String name, int port, String dir) {
		return Requirement.service(name + " sidecar", "http://127.0.0.1:" + port + "/health",
			"run sidecars/" + dir + "/setup.sh once, then sidecars/" + dir + "/run.sh (it binds " + port + ")");
	}

	// ------------------------------------------------------------------------
	// depthmap
	// ------------------------------------------------------------------------

	public static DocsFixtureRecipe depthmap() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "depthmap";
			}

			@Override
			public Requirement requirement() {
				return sidecar("depth", new DepthmapNodeOptions().getDepthPort(), "depth");
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				// A portrait, because relative depth is easiest to read when the picture has an
				// obvious near thing and an obvious far thing — here a head in front of a wall.
				var media = env.image1();
				DepthmapNodeOptions options = new DepthmapNodeOptions();
				DepthmapNode node = new DepthmapNode(null, env.cortexOptions("depthmap"), options,
					new DepthmapClient(options.getDepthHost(), options.getDepthPort(), (int) options.getTimeoutMs()));
				node.initialize();
				return new Outcome(node.process(NodeContext.create(env.media(media))), env.displayPath(media),
					new JsonObject().put("mode", options.getMode().name()).put("maxDim", options.getMaxDim()));
			}
		};
	}

	// ------------------------------------------------------------------------
	// sam2
	// ------------------------------------------------------------------------

	/**
	 * Segment-everything on a still, and deliberately not the prompted mode.
	 *
	 * <p>
	 * PROMPTED is the more interesting wiring, but photographing it would need the YOLO natives and
	 * an ONNX model <em>on top of</em> the sidecar — two requirements for one picture, and an
	 * aborted fixture teaches nobody anything. AUTOMATIC needs only the sidecar and produces the
	 * output that actually shows what this node does.
	 * </p>
	 *
	 * <p>
	 * Previews are switched on, because the honest picture here is the {@code overlay} port. The
	 * {@code masks} port is {@code MANY} and the runtime previews only its first element, so without
	 * the overlay a segment-everything run would illustrate itself with one cut-out.
	 * </p>
	 */
	public static DocsFixtureRecipe sam2() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "sam2";
			}

			@Override
			public Requirement requirement() {
				return sidecar("sam2", new Sam2NodeOptions().getSam2Port(), "sam2");
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				// The same portrait the depthmap page uses, so a reader following the catalogue sees
				// one photograph answered three different ways.
				var media = env.image1();
				Sam2NodeOptions options = new Sam2NodeOptions();
				Sam2Node node = new Sam2Node(null, env.cortexOptions("sam2"), options,
					new Sam2Client(options.getSam2Host(), options.getSam2Port(), (int) options.getTimeoutMs()),
					new Sam2FrameSampler());
				node.initialize();
				return new Outcome(node.process(context(env.media(media))), env.displayPath(media),
					new JsonObject()
						.put("mode", options.getMode().name())
						.put("maxDim", options.getMaxDim())
						.put("pointsPerSide", options.getPointsPerSide())
						.put("minMaskArea", options.getMinMaskArea()));
			}
		};
	}

	// ------------------------------------------------------------------------
	// sentiment
	// ------------------------------------------------------------------------

	/**
	 * German, and deliberately so.
	 *
	 * <p>
	 * The node's default language is {@code auto}, which is a routing decision — the sidecar detects
	 * the language and picks a checkpoint per language. Feeding it English would exercise the
	 * routing without showing it: the picture would be indistinguishable from a node with no routing
	 * at all. This is the same text the translate page uses, so a reader following the catalogue
	 * sees one transcript handled by three different nodes.
	 * </p>
	 */
	public static DocsFixtureRecipe sentiment() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "sentiment";
			}

			@Override
			public Requirement requirement() {
				return sidecar("sentiment", new SentimentNodeOptions().getSentimentPort(), "sentiment");
			}

			@Override
			public List<Upstream> upstream() {
				return List.of(new Upstream("whisper", "transcript", "text"));
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				SentimentNodeOptions options = new SentimentNodeOptions();
				SentimentNode node = new SentimentNode(null, env.cortexOptions("sentiment"), options,
					new SentimentClient(options.getSentimentHost(), options.getSentimentPort()));
				node.initialize();
				var media = env.video1();
				NodeContext<LoomMedia> ctx = NodeContext.create(env.media(media),
					NodeInputs.builder().input(SentimentNode.IN_TEXT, LlmRecipes.TRANSCRIPT).build());
				return new Outcome(node.process(ctx), env.displayPath(media),
					new JsonObject().put("language", options.getLanguage()));
			}
		};
	}

	// ------------------------------------------------------------------------
	// tts
	// ------------------------------------------------------------------------

	/**
	 * German at the shipped default, speaking the transcript the rest of this family works on.
	 *
	 * <p>
	 * The node ships {@code language=de, voice=Jakob}, which routes to Orpheus/Kartoffel rather than
	 * to the lighter English Kokoro path — so running it as shipped is also running the heavier of
	 * its two engines. The Kartoffel checkpoint is HF-gated; the sidecar's README names
	 * {@code ORPHEUS_REPO_DE=Thorsten-Voice/tv-orpheus-v1} as the ungated substitute, and either
	 * satisfies this recipe because the node only ever sees a WAV.
	 * </p>
	 */
	public static DocsFixtureRecipe tts() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "tts";
			}

			@Override
			public Requirement requirement() {
				return sidecar("tts", new TtsNodeOptions().getTtsPort(), "tts");
			}

			@Override
			public List<Upstream> upstream() {
				return List.of(new Upstream("whisper", "transcript", "text"));
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				TtsNodeOptions options = new TtsNodeOptions();
				TtsNode node = new TtsNode(null, env.cortexOptions("tts"), options,
					new TtsClient(options.getTtsHost(), options.getTtsPort()));
				node.initialize();
				var media = env.video1();
				NodeContext<LoomMedia> ctx = NodeContext.create(env.media(media),
					NodeInputs.builder().input(TtsNode.IN_TEXT, LlmRecipes.TRANSCRIPT).build());
				return new Outcome(node.process(ctx), env.displayPath(media),
					new JsonObject().put("language", options.getLanguage()).put("voice", options.getVoice()));
			}
		};
	}

	// ------------------------------------------------------------------------
	// imagegen
	// ------------------------------------------------------------------------

	/**
	 * Remix, not generate — the mode the node exists for.
	 *
	 * <p>
	 * {@code GENERATE} is the shipped default and it ignores the media entirely: it sends the
	 * {@code prompt} option to {@code /generate} and returns whatever comes back, so the picture
	 * would show a node with an input port it did not read. {@code REMIX} is the one the sidecar's
	 * own README calls "the endpoint the node calls" — it loads the asset, sends it with a prompt
	 * and gets a variation of that image back, which is a node doing something to the media in front
	 * of it. The mode is recorded in {@code nodeData} so the page shows the setting it was taken at.
	 * </p>
	 *
	 * <p>
	 * The model is whatever the sidecar has loaded. It defaults to SDXL-Turbo, which is ungated;
	 * the Ideogram weights it is named after need an access token and a licence acceptance, and
	 * the node cannot tell the difference because it only ever receives a PNG.
	 * </p>
	 */
	public static DocsFixtureRecipe imagegen() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "imagegen";
			}

			@Override
			public Requirement requirement() {
				return sidecar("imagegen", new ImageGenNodeOptions().getPort(), "ideogram-sidecar");
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				ImageGenNodeOptions options = new ImageGenNodeOptions()
					.setMode(ImageGenMode.REMIX)
					.setPrompt("the same portrait as an oil painting, warm light, visible brush strokes")
					.setStrength(0.55)
					.setSteps(6)
					// Fixed, so a regeneration of this page produces the same picture rather than a
					// different one that is equally true and reads as an unexplained change.
					.setSeed(7);
				ImageGenNode node = new ImageGenNode(null, env.cortexOptions("imagegen"), options,
					new ImageGenClient(options.getHost(), options.getPort(),
						options.getGenerateEndpoint(), options.getRemixEndpoint(), (int) options.getTimeoutMs()));
				node.initialize();
				var media = env.image1();
				return new Outcome(node.process(context(env.media(media))), env.displayPath(media),
					new JsonObject()
						.put("mode", options.getMode().name())
						.put("prompt", options.getPrompt())
						.put("strength", options.getStrength())
						.put("steps", options.getSteps())
						.put("seed", options.getSeed()));
			}
		};
	}

	// ------------------------------------------------------------------------
	// videogen
	// ------------------------------------------------------------------------

	/**
	 * Animate, not generate — for the same reason as {@link #imagegen()}.
	 *
	 * <p>
	 * {@code GENERATE} is the shipped default and never looks at the media, so the card would show a
	 * node ignoring its own input port. {@code ANIMATE} takes the asset as the first frame and moves
	 * it, which is what wiring this node into a pipeline is for.
	 * </p>
	 *
	 * <p>
	 * Everything is left at the shipped resolution and frame count. The only deviation is
	 * {@code steps}, lowered from 40 because this is a 46B model quantized onto one card and the
	 * documentation does not need the extra minutes — the setting is recorded in {@code nodeData} so
	 * the page shows what it was taken at.
	 * </p>
	 */
	public static DocsFixtureRecipe videogen() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "videogen";
			}

			@Override
			public Requirement requirement() {
				return sidecar("videogen", new VideoGenNodeOptions().getPort(), "ltx2-sidecar");
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				VideoGenNodeOptions options = new VideoGenNodeOptions()
					.setMode(VideoGenMode.ANIMATE)
					.setPrompt("the man turns his head slowly towards the camera and smiles")
					.setSteps(20)
					.setSeed(7);
				VideoGenNode node = new VideoGenNode(null, env.cortexOptions("videogen"), options,
					new VideoGenClient(options.getHost(), options.getPort(),
						options.getGenerateEndpoint(), options.getAnimateEndpoint(), (int) options.getTimeoutMs()));
				node.initialize();
				var media = env.image1();
				return new Outcome(node.process(context(env.media(media))), env.displayPath(media),
					new JsonObject()
						.put("mode", options.getMode().name())
						.put("prompt", options.getPrompt())
						.put("width", options.getWidth())
						.put("height", options.getHeight())
						.put("numFrames", options.getNumFrames())
						.put("fps", options.getFps())
						.put("steps", options.getSteps())
						.put("seed", options.getSeed()));
			}
		};
	}

	// ------------------------------------------------------------------------
	// whisper
	// ------------------------------------------------------------------------

	/**
	 * Real speech through the real whisper.cpp binding.
	 *
	 * <p>
	 * The model is a file rather than a service, and it is not in the repository — 1.6 GB of
	 * weights. The requirement points at wherever {@code WhisperOptions.modelPath} says, so a
	 * machine that has run {@code whisper.cpp/models/download-ggml-model.sh} satisfies it and one
	 * that has not aborts with that command instead of failing inside JNI.
	 * </p>
	 */
	public static DocsFixtureRecipe whisper(Path modelPath) {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "whisper";
			}

			@Override
			public Requirement requirement() {
				return both(
					Requirement.file(modelPath,
						"download a ggml Whisper model, e.g. whisper.cpp/models/download-ggml-model.sh large-v3-turbo, "
							+ "and point -Dloom.docsWhisperModel at it"),
					Requirement.of(FixtureEnv.hasFfmpeg(), "ffmpeg on the path",
						"install ffmpeg — the corpus speech recording has to be remuxed, see FixtureEnv.speechWav()"));
			}

			@Override
			public List<Upstream> upstream() {
				// The audio branch of the exclusive input group, because that is what ran. Every
				// video in the corpus is silent, so the video branch has nothing to transcribe.
				return List.of(new Upstream("filesystem-source", "media", "audio"));
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				Path speech = env.speechWav();
				WhisperOptions options = new WhisperOptions();
				options.setModelPath(modelPath.toString());
				WhisperNode node = new WhisperNode(null, env.cortexOptions("whisper"), options,
					new WhisperMediaProcessor(options));
				node.initialize();
				return new Outcome(node.process(NodeContext.create(env.media(speech))), speech.toString(),
					new JsonObject().put("modelPath", modelPath.getFileName().toString())
						.put("useGpu", options.isUseGpu()));
			}
		};
	}

	// ------------------------------------------------------------------------
	// scene-layout
	// ------------------------------------------------------------------------

	/**
	 * The one recipe that has to run three nodes to photograph one.
	 *
	 * <p>
	 * Scene layout takes a depth map on one port and boxes on another, and answers who is in front
	 * of whom. Both inputs have to describe <em>the same picture</em> — a depth map of a portrait
	 * next to boxes found in a video is two unrelated facts, and the relations computed from them
	 * would be arithmetic on nonsense. So this pulls one real frame out of the demo clip, writes it
	 * as a still, and runs the real depth sidecar and the real face detector over that one file.
	 * </p>
	 *
	 * <p>
	 * {@link FixtureEnv#TWO_FACE_FRAME} rather than any frame, and a frame rather than the corpus
	 * portrait: one object yields no relations at all, and a card reporting
	 * {@code relation_count: 0} would be a truthful picture of the node doing nothing visible.
	 * </p>
	 */
	public static DocsFixtureRecipe sceneLayout(Path facedetectPack) {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "scene-layout";
			}

			@Override
			public Requirement requirement() {
				// Both the sidecar and the detector: this recipe genuinely needs both, and reporting
				// only the missing sidecar would send a maintainer chasing the wrong thing.
				Requirement depth = sidecar("depth", new DepthmapNodeOptions().getDepthPort(), "depth");
				Requirement pack = Requirement.file(facedetectPack,
					"the InspireFace model pack ships in cortex/nodes/facedetect/core/packs");
				return both(depth, pack);
			}

			@Override
			public List<Upstream> upstream() {
				return List.of(
					new Upstream("depthmap", "meta", "depth"),
					new Upstream("facedetect", "detections", "detections"));
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				Path still = env.frameStill(FixtureEnv.TWO_FACE_FRAME);

				// 1. The real depth sidecar, over that still.
				DepthmapNodeOptions depthOptions = new DepthmapNodeOptions();
				DepthmapNode depthNode = new DepthmapNode(null, env.cortexOptions("scene-layout-depth"), depthOptions,
					new DepthmapClient(depthOptions.getDepthHost(), depthOptions.getDepthPort(),
						(int) depthOptions.getTimeoutMs()));
				depthNode.initialize();
				NodeResult depth = depthNode.process(NodeContext.create(env.media(still)));
				String depthMeta = single(depth, DepthmapNode.OUT_META.id());

				// 2. The real detector, over the same still. The image branch, so the boxes carry the
				// dimensions they were measured against — the video branch cannot, and scene layout
				// would then have to guess the coordinate convention.
				FacedetectNodeOptions faceOptions = new FacedetectNodeOptions();
				faceOptions.setInspirefacePackPath(facedetectPack.toString());
				FacedetectNode faceNode = new FacedetectNode(null, env.cortexOptions("scene-layout-faces"), faceOptions,
					FacedetectNodeModule.inspirefaceDetector(faceOptions),
					new VideoFaceScanner(FacedetectNodeModule.inspirefaceDetector(faceOptions)));
				faceNode.initialize();
				NodeResult faces = faceNode.process(NodeContext.create(env.media(still)));
				List<String> boxes = all(faces, FacedetectNode.OUT_DETECTIONS.id());

				// 3. The node this picture is of, fed the two real results.
				SceneLayoutNodeOptions options = new SceneLayoutNodeOptions();
				SceneLayoutNode node = new SceneLayoutNode(null, env.cortexOptions("scene-layout"), options);
				node.initialize();
				NodeContext<LoomMedia> ctx = NodeContext.create(env.media(still), NodeInputs.builder()
					.input(SceneLayoutNode.IN_DEPTH, depthMeta)
					.inputs(SceneLayoutNode.IN_DETECTIONS, boxes)
					.build());
				return new Outcome(node.process(ctx), still.toString(),
					new JsonObject()
						.put("depthMode", depthOptions.getMode().name())
						.put("sourceFrame", FixtureEnv.TWO_FACE_FRAME));
			}
		};
	}

	// ------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------

	/**
	 * A context with previews switched on.
	 *
	 * <p>
	 * Only matters for the nodes whose output is a picture: the runtime builds the preview from an
	 * {@code artifact/image} port, but only when the run asked for previews. Without this the
	 * imagegen card would name a PNG it produced and show nothing of it.
	 * </p>
	 */
	private static NodeContext<LoomMedia> context(LoomMedia media) {
		return NodeContext.create(media, new NodeInputs(java.util.Map.of(), java.util.Set.of(), null, null, true));
	}

	private static Requirement both(Requirement first, Requirement second) {
		return new Requirement() {
			@Override
			public boolean satisfied() {
				return first.satisfied() && second.satisfied();
			}

			@Override
			public String describe() {
				return first.satisfied() ? second.describe() : first.describe();
			}

			@Override
			public String hint() {
				return first.satisfied() ? second.hint() : first.hint();
			}
		};
	}

	/** The single value a one-cardinality port carried, or null. */
	private static String single(NodeResult result, String port) {
		List<String> values = all(result, port);
		return values.isEmpty() ? null : values.get(0);
	}

	/** Every element a port carried, in element order. */
	private static List<String> all(NodeResult result, String port) {
		List<String> values = new ArrayList<>();
		PortOutput output = result.getOutputs().get(port);
		if (output == null) {
			return values;
		}
		for (Object value : output.values()) {
			if (value != null) {
				values.add(String.valueOf(value));
			}
		}
		return values;
	}
}
