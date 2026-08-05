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
import io.metaloom.cortex.node.scenelayout.SceneLayoutNode;
import io.metaloom.cortex.node.scenelayout.SceneLayoutNodeOptions;
import io.metaloom.cortex.node.sentiment.SentimentClient;
import io.metaloom.cortex.node.sentiment.SentimentNode;
import io.metaloom.cortex.node.sentiment.SentimentNodeOptions;
import io.metaloom.cortex.node.tts.TtsClient;
import io.metaloom.cortex.node.tts.TtsNode;
import io.metaloom.cortex.node.tts.TtsNodeOptions;
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
