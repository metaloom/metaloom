package io.metaloom.cortex.node.hello;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;

/**
 * Hello-world example of a custom Cortex node that processes assets.
 *
 * <p>This node demonstrates the core patterns for building a custom node:
 * <ul>
 *   <li>Extending {@link AbstractMediaNode} so the pipeline handles lifecycle, error handling,
 *       and the "enabled / exists / processable" checks automatically.</li>
 *   <li>Declaring <b>typed ports</b> as public static constants and publishing two outputs
 *       ({@code file_size} and {@code word_count}) via {@link NodeContext#output(OutputPort, Object)}
 *       so downstream nodes can be wired to them.</li>
 *   <li>Consuming an <b>input port</b> (e.g. an SHA-256 hash produced by an earlier node) via
 *       {@link NodeContext#input(InputPort)}. Note that the node names its own port and the
 *       <em>edge</em> decides which upstream node fills it - a node id is never named here.</li>
 *   <li>Persisting the result <b>agnostically</b> into the {@code asset_json_comp} table via a thin
 *       Loom REST call ({@code createAssetJsonComp}) — no dedicated component table or DB dependency
 *       required. This is the lightweight, customer-facing persistence path.</li>
 *   <li>Declaring its <b>contract</b> with {@link NodeSpec}, {@link PortDoc} and
 *       {@code @ParamDoc}, so the node appears in the pipeline editor automatically — see below.</li>
 * </ul>
 *
 * <h2>How this node reaches the pipeline editor</h2>
 *
 * <p>
 * The annotations below are the whole mechanism. At startup the worker reflects over this class,
 * builds a descriptor from the port constants and the options fields, and announces it to Loom, which
 * serves it to the editor alongside its own built-in nodes. Nothing has to be added to Loom, and Loom
 * does not have to be rebuilt.
 * </p>
 *
 * <p>
 * Note what the annotations do <em>not</em> contain: no port list, no content types, no parameter
 * names or defaults. Those are read from the declarations right here — the same
 * {@link #IN_HASH} constant this node executes against — so the contract cannot drift from the code.
 * The annotations carry only what reflection cannot know: display names, descriptions, an icon and a
 * palette category.
 * </p>
 *
 * <p>
 * The class is contributed to the harvest by {@code HelloWorldNodeSpecSource}, registered in
 * {@code META-INF/services}.
 * </p>
 */
@NodeSpec(nodeId = "hello-world", name = "Hello World", icon = "description", category = NodeCategory.ANALYSIS,
	description = "Example custom node: reports a file's size and estimated word count.")
public class HelloWorldNode extends AbstractMediaNode<HelloWorldNodeOptions> {

	private static final Logger log = LoggerFactory.getLogger(HelloWorldNode.class);

	/**
	 * An optional hash handed to us by whatever the pipeline author wired into this port.
	 *
	 * <p>
	 * The port is named {@code hash} because that is what <em>this</em> node calls it. Which node
	 * produces it is the edge's business - that is the whole point of a port, and why renaming a
	 * neighbour in the editor can no longer silently starve this node.
	 * </p>
	 */
	@PortDoc(label = "Hash", description = "An SHA-256 hash produced upstream. Optional - the node runs without it.",
		required = false)
	public static final InputPort<String> IN_HASH = InputPort.one("hash", ContentTypeRegistry.HASH_SHA256, String.class);

	/** The file size in bytes. {@code scalar/integer} always arrives as a {@code Long}. */
	@PortDoc(label = "File Size", description = "Size of the media file in bytes")
	public static final OutputPort<Long> OUT_FILE_SIZE = OutputPort.one("file_size", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	/** The estimated word count. */
	@PortDoc(label = "Word Count", description = "Estimated number of whitespace-separated words in the file")
	public static final OutputPort<Long> OUT_WORD_COUNT = OutputPort.one("word_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	/** Schema-type label under which the result payload is stored in {@code asset_json_comp}. */
	public static final String SCHEMA_TYPE = "hello-world";

	@Inject
	public HelloWorldNode(@Nullable LoomClient client, CortexOptions cortexOption, HelloWorldNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public String name() {
		return "hello-world";
	}

	/**
	 * This node can process any media type — images, videos, audio, documents.
	 * Override this to restrict processing to specific types (e.g. {@code ctx.media().isVideo()}).
	 */
	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return true;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();

		// --- Reading an input port ---
		// If the pipeline author wired a hash producer into our "hash" port, its value is here.
		// ctx.isWired(IN_HASH) distinguishes "nothing wired" from "wired but empty".
		String upstreamHash = ctx.input(IN_HASH);
		if (upstreamHash != null) {
			log.info("SHA-256 hash on the '{}' input port: {}", IN_HASH.id(), upstreamHash);
		}

		// --- Output 1: file size ---
		long fileSize = media.size();
		ctx.output(OUT_FILE_SIZE, fileSize);

		// --- Output 2: word count ---
		long wordCount = countWords(media.file());
		ctx.output(OUT_WORD_COUNT, wordCount);

		// Log what we did
		ctx.info("Computed file_size=" + fileSize + ", word_count=" + wordCount);

		// --- Persist the result agnostically into asset_json_comp via REST ---
		// When running online (a LoomClient is configured and the asset is known), the result is
		// posted to the generic JSON component sink. The payload shape is opaque to Loom — no
		// dedicated table is required — which keeps this example lightweight and customer-facing.
		if (!isOfflineMode() && asset != null) {
			JsonObject data = new JsonObject()
				.put(OUT_FILE_SIZE.id(), fileSize)
				.put(OUT_WORD_COUNT.id(), wordCount);
			JsonCompCreateRequest request = new JsonCompCreateRequest()
				.setNodeKind(name())        // "hello-world" — part of the component identity
				.setSchemaType(SCHEMA_TYPE) // shape label for the payload
				.setData(data);
			client().createAssetJsonComp(asset.getUuid(), request).sync();
			log.info("Persisted hello-world result for asset {} into asset_json_comp", asset.getUuid());
		}

		// Feed the outputs downstream and mark the result as computed.
		return ctx.origin(COMPUTED).next();
	}

	/**
	 * Naive word counter: reads the file as text and splits on whitespace.
	 * For binary files (images, videos) this will return 0.
	 */
	private long countWords(File file) {
		long count = 0;
		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty()) {
					count += trimmed.split("\\s+").length;
				}
			}
		} catch (IOException e) {
			log.debug("Could not read file as text (binary?): {}", e.getMessage());
		}
		return count;
	}
}
