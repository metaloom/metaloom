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
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;

/**
 * Hello-world example of a custom Cortex node that processes assets.
 *
 * <p>This node demonstrates the core patterns for building a custom node:
 * <ul>
 *   <li>Extending {@link AbstractMediaNode} so the pipeline handles lifecycle, error handling,
 *       and the "enabled / exists / processable" checks automatically.</li>
 *   <li>Publishing <b>two named outputs</b> ({@code file_size} and {@code word_count}) via
 *       {@link NodeContext#output(String, Object)} so downstream nodes can consume them.</li>
 *   <li>Consuming <b>upstream outputs</b> from a dependency node (e.g. an SHA-256 hash produced
 *       by an earlier node in the pipeline) via {@link NodeContext#upstreamOutput(String, String)}.</li>
 *   <li>Returning a typed {@link HelloWorldPayload} so the pipeline runner can inspect the result.</li>
 * </ul>
 */
public class HelloWorldNode extends AbstractMediaNode<HelloWorldPayload, HelloWorldNodeOptions> {

	private static final Logger log = LoggerFactory.getLogger(HelloWorldNode.class);

	/** Output key for the file size in bytes. */
	public static final String OUTPUT_FILE_SIZE = "file_size";

	/** Output key for the estimated word count. */
	public static final String OUTPUT_WORD_COUNT = "word_count";

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
	protected NodeResult<HelloWorldPayload> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();

		// --- Reading upstream outputs ---
		// If this node is wired after a "sha256" node in the pipeline graph,
		// you can read its output like this:
		String upstreamHash = ctx.upstreamOutput("sha256", "sha256");
		if (upstreamHash != null) {
			log.info("Upstream SHA-256 hash available: {}", upstreamHash);
		}

		// --- Output 1: file size ---
		long fileSize = media.size();
		ctx.output(OUTPUT_FILE_SIZE, fileSize);

		// --- Output 2: word count ---
		long wordCount = countWords(media.file());
		ctx.output(OUTPUT_WORD_COUNT, wordCount);

		// Log what we did
		ctx.info("Computed file_size=" + fileSize + ", word_count=" + wordCount);

		// Return the typed payload — the pipeline stores it in the NodeResult
		return ctx.origin(COMPUTED).next(HelloWorldPayload.of(fileSize, wordCount));
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
