package io.metaloom.cortex.node.thumbnail;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.metaloom.video4j.preview.PreviewGenerator;

@NodeSpec(nodeId = "thumbnail", name = "Thumbnail Generator", icon = "grid_view", category = NodeCategory.TRANSFORM,
	description = "Generate a thumbnail grid from video or image content.", defaultConcurrency = 2)
public class ThumbnailNode extends AbstractMediaNode<ThumbnailNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(ThumbnailNode.class);

	@PortDoc(label = "Media", description = "The image or video to render a preview from")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	/**
	 * Whether the file is whole. Optional and declared rather than looked up: this used to be
	 * {@code ctx.upstreamOutput("consistency", "is_complete")}, which silently produced nothing
	 * the moment a pipeline author named the node anything other than {@code consistency}.
	 */
	@PortDoc(label = "Is Complete", required = false,
		description = "Whether the file is whole; an incomplete one is skipped unless processIncomplete is set")
	public static final InputPort<Boolean> IN_IS_COMPLETE = InputPort.one("is_complete", ContentTypeRegistry.SCALAR_BOOLEAN, Boolean.class);

	@PortDoc(label = "Thumbnail", description = "The rendered preview grid in the worker's local cache; wire it into a sink to keep it")
	public static final OutputPort<String> OUT_THUMBNAIL = OutputPort.one("thumbnail", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item")
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	/** In-heap skip cache of the generated thumbnail path, keyed by media path, to avoid re-rendering the contact sheet within this worker's lifetime.
	 * The rendered file itself is a durable local artifact under {@code metaPath/thumbnail_bin}. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(50_000);

	private final PreviewGenerator gen;
	private final CortexOptions cortexOptions;

	@Inject
	public ThumbnailNode(@Nullable LoomClient client, CortexOptions options, ThumbnailNodeOptions actionOptions) {
		super(client, options, actionOptions);
		this.cortexOptions = options;
		int tileSize = actionOptions.getTileSize();
		int cols = actionOptions.getCols();
		int rows = actionOptions.getRows();
		this.gen = new PreviewGenerator(tileSize, cols, rows);
	}

	@Override
	public void initialize() {
		Video4j.init();
	}

	@Override
	public String name() {
		return "thumbnail";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (!ctx.media().isVideo()) {
			return false;
		}
		if (!options().isProcessIncomplete()) {
			Boolean isComplete = ctx.input(IN_IS_COMPLETE);
			if (isComplete != null && !isComplete) {
				return false;
			}
		}
		return true;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		try {
			String path = media.absolutePath();
			String cached = resultCache.get(path);
			if (cached != null) {
				ctx.output(OUT_FLAG, "DONE");
				ctx.output(OUT_THUMBNAIL, cached);
				return ctx.origin(LOCAL).next();
			}
			try (VideoFile video = Videos.open(path)) {
				Path thumbnailPath = resolveThumbnailPath(media);
				Files.createDirectories(thumbnailPath.getParent());
				try (OutputStream os = new FileOutputStream(thumbnailPath.toFile())) {
					gen.save(video, os);
					ctx.print("DONE", "");
					ctx.output(OUT_FLAG, "DONE");
					ctx.output(OUT_THUMBNAIL, thumbnailPath.toString());
					resultCache.put(path, thumbnailPath.toString());
				}
			}
			// The thumbnail bytes live in the local thumbnail_bin cache; record the ledger marker that this node produced it for the asset. Uploading
			// the bytes into the asset binary subsystem requires a target library the node does not have, so that remains a follow-up.
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, null);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to compute thumbnail", e);
			ctx.output(OUT_FLAG, "FAILED");
			error(media, "NULL");
			return ctx.failure(e.getMessage()).next();
		}
	}

	private Path resolveThumbnailPath(LoomMedia media) {
		SHA512 hash = media.getSHA512();
		String fileName = hash + ".thumb";
		Path basePath = cortexOptions.getMetaPath().resolve("thumbnail_bin");
		Path dirPath = HashUtils.segmentPath(basePath, hash);
		return dirPath.resolve(fileName);
	}
}
