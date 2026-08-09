package io.metaloom.cortex.node.dedup;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.fs.FileUtils;
import io.metaloom.utils.hash.SHA512;

/*
 * 🔴 One class, two node ids. Dagger binds this node under both "hash-dedup" and "sha512-dedup" (the
 * name() this node reports); @NodeSpec carries a single nodeId, so it declares the one the descriptor
 * provider actually describes - "hash-dedup". "sha512-dedup" is a pure alias: the provider has never
 * described it and it exists only so an older pipeline definition still resolves. See the sweep report.
 */
@NodeSpec(nodeId = "hash-dedup", name = "Hash Deduplication", icon = "file_copy", category = NodeCategory.OUTPUT,
	description = "Detect duplicate files by hash equality and report them on the duplicate port. Wire that port into a move node to relocate them.",
	defaultMode = io.metaloom.loom.nodes.spec.NodeMode.SEQUENTIAL,
	// The dedup descriptors have only ever advertised `enabled` of the three common knobs.
	parameters = {
		@ParamOverride(key = "processIncomplete", hidden = true),
		@ParamOverride(key = "retryFailed", hidden = true)
	})
public class HashDedupNode extends AbstractMediaNode<DedupNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(HashDedupNode.class);

	/**
	 * The hash that identifies the file's content.
	 *
	 * <p>
	 * Declared so the node's own contract states the input its descriptor has always advertised. The
	 * node reads the SHA-512 off the media handle rather than off this port, so wiring it is what
	 * orders a hash node before this one, not what feeds it.
	 * </p>
	 */
	@PortDoc(label = "Hash", description = "Any content hash. Two files whose hashes match are treated as the same file")
	public static final InputPort<String> IN_HASH = InputPort.one("hash", ContentTypeRegistry.HASH_ANY, String.class);

	/**
	 * The item, when an asset with identical content already exists elsewhere.
	 *
	 * <p>
	 * Selective: silence on this port is the "nothing to do" signal, exactly as a filter's bucket ports work. Wire it into a {@code move} node to
	 * relocate the duplicate - this node used to do that itself, hard-wired to a {@code dupFolder} nobody could redirect.
	 * </p>
	 */
	@PortDoc(label = "Duplicate", description = "The item, when an asset with identical content already exists elsewhere")
	public static final OutputPort<String> OUT_DUPLICATE = OutputPort.one("duplicate", ContentTypeRegistry.MEDIA_ANY, String.class);

	@PortDoc(label = "Original", description = "Path of the copy Loom already knew about")
	public static final OutputPort<String> OUT_ORIGINAL = OutputPort.one("original", ContentTypeRegistry.SCALAR_STRING, String.class);

	private final LoomMediaLoader loader;

	@Inject
	public HashDedupNode(@Nullable LoomClient client, CortexOptions cortexOptions, DedupNodeOptions options, LoomMediaLoader loader) {
		super(client, cortexOptions, options);
		this.loader = loader;
	}

	@Override
	public String name() {
		return "sha512-dedup";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().getSHA512() != null;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		if (isOfflineMode()) {
			return ctx.skipped("offline mode").next();
		} else {
			LoomMedia media = ctx.media();
			SHA512 sha512 = media.getSHA512();

			// We found an item. Lets check it.
			if (asset != null) {
				if (asset.getFile().getFilename() == null) {
					return ctx.info("Source from db has no current path").next();
				}
				File dbFile = new File(asset.getFile().getFilename());
				if (!dbFile.exists()) {
					return ctx.info("Source from db not found for currentpath").next();
				}
				if (dbFile.equals(media.file())) {
					// All okay - its the same file
					return ctx.info("same file").next();
				} else {
					// AssetRequest newAsset = AssetRequest.newBuilder()
					// .setSha512Sum(sha512.toString())
					// .setFilename(media.absolutePath())
					// .build();
					// client().storeAsset(sha512)
					LoomMedia foundMedia = loader.load(dbFile.toPath());
					if (!foundMedia.exists() || !media.exists()) {
						return ctx.info("Source or dup not found").next();
					}

					// The hash matches the local file
					if (sha512.equals(foundMedia.getSHA512())) {
						String pathA = media.absolutePath();
						String pathB = foundMedia.absolutePath();

						if (foundMedia.size() != dbFile.length()) {
							// This used to block on System.in.read(), which hangs a headless worker forever on a condition that needs a human to
							// look at two files. Report it and leave both files alone instead: moving one when the records disagree is exactly the
							// case where a move could destroy the only good copy.
							log.error("Inconsistent file found. The file sizes do not match. Please verify these files manually - skipping.");
							log.error("[A]: " + media.shortHash() + " E: " + media.exists() + " (" + dbFile.length() + " bytes) " + " => " + pathA);
							log.error(
								"[B]: " + foundMedia.shortHash() + " E: " + foundMedia.exists() + " (" + foundMedia.size() + ") " + "  => " + pathB);
							return ctx.skipped("size mismatch between the local file and the database record").next();
						}
						if (pathA.equalsIgnoreCase(pathB)) {
							return ctx.info("same file").next();
						} else {
							if (log.isDebugEnabled()) {
								log.debug("[A]: " + media.shortHash() + " E: " + media.exists() + " => " + pathA);
								log.debug("[B]: " + media.shortHash() + " E: " + foundMedia.exists() + " => " + pathB);
							}
							// This node used to move the file itself, into a dupFolder that only worker YAML could set.
							// It now reports the finding and lets the pipeline author decide: wire 'duplicate' into a
							// move node and the destination, the conflict policy and whether the original survives all
							// become properties of the graph rather than of this node.
							print(ctx, "DUPLICATE", "[" + pathA + "] duplicates [" + pathB + "]");
							ctx.output(OUT_DUPLICATE, pathA);
							ctx.output(OUT_ORIGINAL, pathB);
							// Ledger-only: the finding is the output, and there is no typed payload for it.
							recordNodeResult(asset, ctx, ResultState.SUCCESS, "duplicate of " + pathB, null, null);
							return ctx.next();
						}
					} else {
						return ctx.next();
					}
				}
			} else {
				return ctx.skipped("no duplicate found").next();
			}
		}

	}

}
