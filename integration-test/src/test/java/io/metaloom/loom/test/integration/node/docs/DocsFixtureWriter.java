package io.metaloom.loom.test.integration.node.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.runtime.NodePreviews;
import io.metaloom.cortex.runtime.NodeResultMapper;
import io.metaloom.loom.pipeline.model.NodePreview;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Turns what a node actually produced into the fixture the screenshot capture replays.
 *
 * <h2>Why it goes through the wire mapper</h2>
 *
 * <p>
 * A node's own {@link NodeResult} is Cortex-internal. What the debugging view renders is the
 * <em>wire</em> shape — coerced against each port's declared content type, elements stamped with
 * their origin, previews generated for the image-bearing ports and merged with the ones the node
 * wrote itself. Reproducing any of that by hand in a fixture is how a screenshot ends up showing a
 * payload the product cannot emit, so this calls {@link NodeResultMapper#toWire} and writes the
 * answer verbatim.
 * </p>
 *
 * <p>
 * That mapper call is also the only thing that generates previews at all. {@code NodePreviews} lives
 * in the node runtime, not in the node: {@code node.process(...)} returns image previews only for
 * {@code facedetect}, which authors its own. Every other picture in the debugging view — the
 * thumbnail contact sheet, a manipulated image, a depth map — is built from an {@code artifact/image}
 * port by the runtime, and a generator that skipped this step would quietly produce fixtures with no
 * pictures in them and screenshots to match.
 * </p>
 *
 * <h2>Layout</h2>
 *
 * <p>
 * One directory per kind under {@code loom-ui/scripts/fixtures/nodes/}, holding {@code fixture.json}
 * plus one file per preview. Per-kind rather than one shared manifest because this generator is
 * inherently partial — one node's service is up and thirty others are not — and a single file would
 * be rewritten wholesale by every partial run, turning a one-node change into a thirty-node diff.
 * </p>
 */
public final class DocsFixtureWriter {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Path root;

	/** Which generator wrote the fixture — the dedup one is a different class, see {@code generatedBy}. */
	private final String generatedBy;

	public DocsFixtureWriter(Path root) {
		this(root, DocsFixtureGenerator.class.getName());
	}

	public DocsFixtureWriter(Path root, String generatedBy) {
		this.root = root;
		this.generatedBy = generatedBy;
	}

	/**
	 * Write one node's fixture.
	 *
	 * @param recipe  what was run, and under what requirement
	 * @param result  what it produced
	 * @param mediaPath the file it ran over, as the debugging view would show it
	 * @param nodeData the node options the run used — needed by the four kinds that resolve their
	 *                 ports from saved options rather than from the descriptor, which would
	 *                 otherwise render portless
	 */
	public void write(DocsFixtureRecipe recipe, NodeResult result, String mediaPath, JsonObject nodeData)
		throws IOException {
		// A failed run must never become documentation. It is the loudest possible way for a recipe
		// to be wrong about the node it is describing — the `script` recipe published
		// "ReferenceError: text is not defined" on its first run, because it had invented binding
		// names — and a page showing a node erroring is worse than a page showing nothing.
		//
		// Nothing here decides that a failure is interesting; if one ever is, the page should say so
		// in prose and this check should grow an explicit opt-in rather than being relaxed.
		if (result.getState() != ResultState.SUCCESS) {
			throw new IllegalStateException(recipe.kind() + ": the node did not succeed ("
				+ result.getState() + ": " + result.getMessage() + ") — fix the recipe rather than "
				+ "publishing a picture of a broken node");
		}

		// A success that emitted nothing is not a success this can photograph — and, worse, it is
		// what a *failure* looks like from here. `NodeContextImpl.next()` reads `skipReason` but
		// not `failureCause`, so the nineteen nodes that end a catch block with
		// `ctx.failure(msg).next()` return SUCCESS with the message dropped: the sentiment recipe's
		// first run reported COMPLETED for a request the sidecar had answered with a 500. Until
		// that is fixed in the product, "SUCCESS with no outputs at all" is the only signal this
		// generator has, and it has to be treated as the failure it usually is. A node that
		// genuinely emits nothing has nothing for a debugging view to show either way.
		if (result.getOutputs().isEmpty() && !recipe.emitsNoPorts()) {
			throw new IllegalStateException(recipe.kind() + ": the node reported success but emitted "
				+ "nothing on any port. That is also how a caught exception surfaces — see "
				+ "NodeContextImpl.next() — so check the service's own log before trusting the state");
		}

		// Same reason, one step further along: the nodes with a `flag` port say FAILED on it and
		// then return SUCCESS anyway, so a run that failed arrives here with outputs and a green
		// state. The flag is the node's own verdict on itself and it outranks the result state.
		String flag = flagValue(result);
		if ("FAILED".equals(flag)) {
			throw new IllegalStateException(recipe.kind() + ": the node emitted flag=FAILED. The "
				+ "result state says otherwise, and the flag is the one the node actually set");
		}

		// And the quietest failure of all: every port populated, nothing in any of them. Whisper
		// answered `{"segments":[]}` for a video with no audio stream — a green node, one port, a
		// well-formed payload, and no transcript. Nothing above catches that, and it would have
		// shipped as a picture of a working transcriber.
		if (allEmpty(result) && !recipe.emitsNoPorts()) {
			throw new IllegalStateException(recipe.kind() + ": every output port is empty. The node "
				+ "ran and produced nothing — check the input it was given before blaming the node");
		}

		Path dir = root.resolve(recipe.kind());
		Files.createDirectories(dir);

		// The two halves of `NodeResultMapper.toWire`, called separately rather than through it.
		//
		// `toWire` reads the node's graph id off the result, and a node constructed directly — as
		// every recipe does, because standing up an engine to take a screenshot would be absurd —
		// never received one. Calling the same two steps with the kind as the id keeps the coercion
		// and the preview generation identical to production while supplying the one thing a
		// graph-less node cannot know about itself.
		Map<String, PortPayload> outputs = NodeResultMapper.toPayloads("item-0", 0, result.getOutputs());
		Map<String, NodePreview> previews =
			NodePreviews.merge(NodePreviews.build(outputs), result.getPreviews());

		JsonObject previewIndex = new JsonObject();
		if (previews != null) {
			for (Map.Entry<String, NodePreview> entry : previews.entrySet()) {
				NodePreview preview = entry.getValue();
				JsonObject meta = new JsonObject();
				if (preview.getData() != null && preview.getData().length > 0) {
					// `#` is legal in a port preview key and hostile in a file name.
					String file = entry.getKey().replace('#', '-') + extensionFor(preview.getMimeType());
					atomicWrite(dir.resolve(file), preview.getData());
					meta.put("file", file)
						.put("mimeType", preview.getMimeType())
						.put("width", preview.getWidth())
						.put("height", preview.getHeight());
				}
				if (preview.getMarkdown() != null) {
					meta.put("markdown", preview.getMarkdown());
				}
				if (preview.getFrame() != null) {
					meta.put("frame", preview.getFrame());
				}
				if (preview.getSkippedReason() != null) {
					meta.put("skippedReason", preview.getSkippedReason());
				}
				if (!meta.isEmpty()) {
					previewIndex.put(entry.getKey(), meta);
				}
			}
		}

		JsonObject fixture = new JsonObject()
			.put("kind", recipe.kind())
			.put("generatedBy", generatedBy)
			// "real" means the node ran against the thing it talks to in production. The capture
			// refuses anything else outside a reviewed allowlist, because a stubbed backend produces
			// a screenshot of a decision nothing made.
			.put("backend", recipe.backend())
			.put("requirement", recipe.requirement().describe())
			.put("state", wireState(result))
			.put("durationMs", result.getDurationMs())
			.put("message", result.getMessage())
			.put("source", new JsonObject().put("path", mediaPath))
			.put("nodeData", nodeData == null ? new JsonObject() : nodeData)
			.put("upstream", upstreamOf(recipe))
			// Already in the REST shape, so the capture script pastes it straight into its mocked
			// `/tasks` response and composing a graph is concatenation rather than translation.
			.put("outputs", new JsonObject(MAPPER.writeValueAsString(outputs)))
			.put("previews", previewIndex);

		atomicWrite(dir.resolve("fixture.json"), fixture.encodePrettily().getBytes());
		System.out.println("  " + recipe.kind() + ": " + wireState(result)
			+ ", " + outputs.size() + " ports, " + previewIndex.size() + " previews");
	}

	/**
	 * Cortex's terminal state under the name the debugging view shows.
	 *
	 * <p>
	 * The mapping is the engine's own ({@code SUCCESS→COMPLETED}), spelled out here because the
	 * method that owns it is private to the mapper. Getting it wrong is the exact mistake the first
	 * generation of these fixtures made — it published a state no node emits.
	 * </p>
	 */
	private static String wireState(NodeResult result) {
		return switch (result.getState()) {
			case SUCCESS -> "COMPLETED";
			case SKIPPED -> "SKIPPED";
			case FAILED -> "FAILED";
			default -> String.valueOf(result.getState());
		};
	}

	/**
	 * What the node said on its own {@code flag} port, or null if it has none.
	 *
	 * <p>
	 * Several nodes carry a {@code DONE} / {@code FAILED} marker there, and it is the only place
	 * some of them record that the run went wrong — see the guard in {@link #write}.
	 * </p>
	 */
	private static String flagValue(NodeResult result) {
		var output = result.getOutputs().get("flag");
		if (output == null || output.values().isEmpty()) {
			return null;
		}
		Object value = output.values().get(0);
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * Did every port come back with nothing in it?
	 *
	 * <p>
	 * "Nothing" is deliberately generous: no elements, a null, a blank string, an empty JSON array
	 * or an empty JSON object. Those are all the shapes a node's zero-result looks like, and none of
	 * them is worth a screenshot. One port carrying anything at all is enough to pass — a node that
	 * legitimately reports "no faces here" alongside a count is still saying something.
	 * </p>
	 */
	private static boolean allEmpty(NodeResult result) {
		for (var output : result.getOutputs().values()) {
			for (Object value : output.values()) {
				if (value == null) {
					continue;
				}
				String text = String.valueOf(value).strip();
				if (!text.isEmpty() && !"[]".equals(text) && !"{}".equals(text)
					&& !text.replaceAll("\\s+", "").matches("\\{\"\\w+\":\\[\\]\\}")) {
					return false;
				}
			}
		}
		return true;
	}

	private static JsonArray upstreamOf(DocsFixtureRecipe recipe) {
		JsonArray array = new JsonArray();
		recipe.upstream().forEach(u -> array.add(new JsonObject()
			.put("kind", u.kind()).put("sourcePort", u.sourcePort()).put("targetPort", u.targetPort())));
		return array;
	}

	private static String extensionFor(String mimeType) {
		if (mimeType == null) {
			return ".bin";
		}
		if (mimeType.contains("png")) {
			return ".png";
		}
		if (mimeType.contains("webp")) {
			return ".webp";
		}
		return ".jpg";
	}

	/**
	 * Write via a temporary file and move.
	 *
	 * <p>
	 * A run that dies partway through must leave the previously committed fixture byte-identical
	 * rather than half-written — the committed output is what the website is built from, and nobody
	 * regenerating one node should be able to corrupt another's.
	 * </p>
	 */
	private static void atomicWrite(Path target, byte[] bytes) throws IOException {
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(tmp, bytes);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
	}
}
