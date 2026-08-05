package io.metaloom.loom.test.integration.node.docs;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.captioning.CaptioningNode;
import io.metaloom.cortex.node.captioning.CaptioningNodeOptions;
import io.metaloom.cortex.node.captioning.SmolVLMClient;
import io.metaloom.cortex.node.captioning.VideoVLMClient;
import io.metaloom.cortex.node.facedescription.FaceDescriptionModel;
import io.metaloom.cortex.node.facedescription.FacedescriptionNode;
import io.metaloom.cortex.node.facedetect.FacedetectNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.node.vlm.VlmChatClient;
import io.metaloom.cortex.node.vlm.VlmNode;
import io.metaloom.cortex.node.vlm.VlmNodeOptions;
import io.metaloom.cortex.node.vlm.VlmNodePrompt;
import io.metaloom.cortex.node.vlm.VlmResponseFormat;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Outcome;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Requirement;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Upstream;
import io.vertx.core.json.JsonObject;

/**
 * The three nodes that need a vision-language model.
 *
 * <p>
 * All three speak the OpenAI chat-completions protocol with an image content part, so one
 * llama.cpp server with a multimodal GGUF satisfies all three:
 * </p>
 *
 * <pre>
 * cd loom-test-env/llamacpp
 * NAME=loom-test-vlm PORT=8000 MODEL=ggml-org/Qwen2.5-VL-7B-Instruct-GGUF:Q4_K_M ./start.sh
 * </pre>
 *
 * <h2>Why the requirement checks for multimodality rather than for a server</h2>
 *
 * <p>
 * A text-only model on the same port answers every request these nodes make. It just answers them
 * without having seen the picture — describing a face it cannot look at, transcribing a page it was
 * never shown. That is the single most misleading thing this harness could publish: a confident,
 * fluent, entirely invented caption, indistinguishable on the page from a real one. So the probe
 * reads {@code /v1/models} and requires the loaded model to advertise {@code multimodal}, and a
 * text model on the right port counts as "not available" rather than as a backend.
 * </p>
 */
public final class VisionRecipes {

	/** Where {@code VlmNodeOptions} and {@code CaptioningNodeOptions} both already point. */
	private static final String VISION_URL = "http://127.0.0.1:8000";

	/** Where {@code FacedescriptionNode.URL} points, as a hardcoded constant. */
	private static final String FACE_URL = "http://127.0.0.1:8080";

	private static final String MODEL =
		System.getProperty("loom.docsVisionModel", "ggml-org/Qwen2.5-VL-7B-Instruct-GGUF:Q4_K_M");

	private VisionRecipes() {
	}

	private static Requirement vision(String baseUrl, String name) {
		return Requirement.of(
			Probe.bodyContains(baseUrl + "/v1/models", "multimodal"),
			"multimodal model on " + baseUrl + " (" + name + ")",
			"cd loom-test-env/llamacpp && NAME=loom-test-vlm PORT=" + port(baseUrl)
				+ " MODEL=ggml-org/Qwen2.5-VL-7B-Instruct-GGUF:Q4_K_M ./start.sh"
				+ " — a text-only model on that port does not count, see VisionRecipes");
	}

	private static String port(String baseUrl) {
		int colon = baseUrl.lastIndexOf(':');
		return colon < 0 ? "8000" : baseUrl.substring(colon + 1);
	}

	// ------------------------------------------------------------------------
	// vlm
	// ------------------------------------------------------------------------

	/**
	 * The general-purpose "ask a question about this picture" node.
	 *
	 * <p>
	 * Its shipped default is the olmOCR preset, which names a checkpoint
	 * ({@code allenai/olmOCR-2-7B-1025-FP8}) that is not the one running here, and whose response
	 * format is a JSON contract only that family emits. Pointing the preset at a different model
	 * would produce a picture of {@code OlmOcrResponseParser} coping, not a picture of the node — so
	 * this configures a plain {@code TEXT} prompt instead and records it in {@code nodeData}, where
	 * the page can show the prompt that produced the answer.
	 * </p>
	 */
	public static DocsFixtureRecipe vlm() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "vlm";
			}

			@Override
			public Requirement requirement() {
				return vision(VISION_URL, "vlm");
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				// A scanned page rather than a photograph: transcription is the job this node was
				// built for, and it is the one answer a reader can check against the picture.
				Path scan = env.inLibrary(Path.of("/opt/metaloom/loom-testdata/ocr/albert_einstein.png"));
				VlmNodePrompt prompt = new VlmNodePrompt();
				prompt.setModel(MODEL);
				prompt.setResponseFormat(VlmResponseFormat.TEXT);
				prompt.setPrompt("Transcribe the text of this page. Reply with the text only, "
					+ "preserving the line breaks, and add nothing of your own.");
				VlmNodeOptions options = new VlmNodeOptions();
				options.setEndpointUrl(VISION_URL);
				options.addPrompt("transcription", prompt);
				VlmNode node = new VlmNode(null, env.cortexOptions("vlm"), options,
					new VlmChatClient(options.getEndpointUrl(), options.getApiKey()));
				node.initialize();
				return new Outcome(node.process(NodeContext.create(env.media(scan))), scan.toString(),
					new JsonObject().put("endpointUrl", VISION_URL).put("prompts", new JsonObject()
						.put("transcription", new JsonObject()
							.put("model", MODEL)
							.put("responseFormat", VlmResponseFormat.TEXT.name())
							.put("prompt", prompt.getPrompt()))));
			}
		};
	}

	// ------------------------------------------------------------------------
	// captioning
	// ------------------------------------------------------------------------

	/**
	 * The video branch, not the image branch.
	 *
	 * <p>
	 * These are two different backends behind one node. Images go to a bespoke SmolVLM server with a
	 * {@code POST /caption} endpoint of its own; video goes to an OpenAI-compatible endpoint as a
	 * batch of sampled frames. Only the second is standing here, and the video branch is also the
	 * one with something to look at — it decodes the clip, samples frames and asks one question
	 * about all of them, which is what the page is trying to explain. The {@code media_alt} input
	 * group is exclusive, so the graph draws the {@code video} port alone.
	 * </p>
	 */
	public static DocsFixtureRecipe captioning() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "captioning";
			}

			@Override
			public Requirement requirement() {
				return vision(VISION_URL, "captioning");
			}

			@Override
			public List<Upstream> upstream() {
				return List.of(new Upstream("filesystem-source", "media", "video"));
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				CaptioningNodeOptions options = new CaptioningNodeOptions();
				// The only deviation from the defaults: the shipped `videoModel` names an AWQ build
				// served by vLLM, and the local server serves a GGUF under its own name.
				options.setVideoModel(MODEL);
				CaptioningNode node = new CaptioningNode(null, env.cortexOptions("captioning"), options,
					new SmolVLMClient(options.getSmolVLMHost(), options.getSmolVLMPort()),
					new VideoVLMClient(options.getVideoEndpointUrl(), options.getVideoModel(),
						options.getVideoApiKey(), options.getMaxTokens(), options.getTemperature()));
				node.initialize();
				var media = env.video1();
				return new Outcome(node.process(NodeContext.create(env.media(media))), env.displayPath(media),
					new JsonObject()
						.put("videoEndpointUrl", options.getVideoEndpointUrl())
						.put("videoModel", MODEL)
						.put("videoStrategy", options.getVideoStrategy().name())
						.put("frameCount", options.getFrameCount()));
			}
		};
	}

	// ------------------------------------------------------------------------
	// facedescription
	// ------------------------------------------------------------------------

	/**
	 * One description per incoming face, from a real model looking at a real crop.
	 *
	 * <p>
	 * This node reads its backend from a {@code public static final String} rather than from its
	 * options, so it can only ever be photographed with a vision model on 8080 — which is also where
	 * the text model used by {@code llm} / {@code translate} / {@code filter} lives. The two cannot
	 * be up at once, and that is a genuine constraint of the node rather than of this harness.
	 * </p>
	 *
	 * <p>
	 * Run over the two-face still so the card shows the node's actual shape: N boxes in, N
	 * descriptions out, aligned by element index.
	 * </p>
	 */
	public static DocsFixtureRecipe facedescription(Path facedetectPack) {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "facedescription";
			}

			@Override
			public Requirement requirement() {
				return vision(FACE_URL, "facedescription — its URL constant is hardcoded to 8080");
			}

			@Override
			public List<Upstream> upstream() {
				return List.of(new Upstream("facedetect", "detections", "detections"));
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				Path still = env.frameStill(FixtureEnv.TWO_FACE_FRAME);

				// The real detector first, so the boxes this node describes are boxes something found.
				FacedetectNodeOptions faceOptions = new FacedetectNodeOptions();
				faceOptions.setInspirefacePackPath(facedetectPack.toString());
				FacedetectNode detect = new FacedetectNode(null, env.cortexOptions("facedescription-faces"),
					faceOptions, FacedetectNodeModule.inspirefaceDetector(faceOptions),
					new io.metaloom.cortex.node.facedetect.video.VideoFaceScanner(
						FacedetectNodeModule.inspirefaceDetector(faceOptions)));
				detect.initialize();
				NodeResult detected = detect.process(NodeContext.create(env.media(still)));

				FacedescriptionNode node = new FacedescriptionNode(null,
					env.cortexOptions("facedescription"), faceOptions, new ObjectMapper(),
					FacedetectNodeModule.inspirefaceDetector(faceOptions));
				node.initialize();
				NodeContext<LoomMedia> ctx = NodeContext.create(env.media(still), NodeInputs.builder()
					.inputs(FacedescriptionNode.IN_DETECTIONS,
						values(detected, FacedetectNode.OUT_DETECTIONS.id()))
					.build());
				return new Outcome(node.process(ctx), still.toString(),
					new JsonObject()
						.put("endpointUrl", FacedescriptionNode.URL)
						// The node's own choice, not this recipe's: MODEL is a private constant, and
						// what actually answers is whatever the server on that URL has loaded.
						.put("model", FaceDescriptionModel.GEMMA3_27B_IT.id())
						.put("servedBy", MODEL)
						.put("sourceFrame", FixtureEnv.TWO_FACE_FRAME));
			}
		};
	}

	/** Kept here so the two recipes that chain a detector share one reading of its output. */
	static List<String> values(NodeResult result, String port) {
		PortOutput output = result.getOutputs().get(port);
		if (output == null) {
			return List.of();
		}
		return output.values().stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
	}
}
