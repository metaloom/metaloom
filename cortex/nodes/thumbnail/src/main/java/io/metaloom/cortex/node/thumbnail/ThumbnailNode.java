package io.metaloom.cortex.node.thumbnail;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.FS;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.XATTR;
import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.media.param.ThumbnailFlag;
import io.metaloom.cortex.api.meta.MetaDataStream;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.payload.ImagePayload;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.metaloom.video4j.preview.PreviewGenerator;

public class ThumbnailNode extends AbstractMediaNode<ThumbnailNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(ThumbnailNode.class);

	public static final NodeOutputKey<String> OUTPUT_THUMBNAIL_FLAG = NodeOutputKey.of("thumbnail_flag", String.class);
	public static final NodeOutputKey<String> OUTPUT_THUMBNAIL_PATH = NodeOutputKey.of("thumbnail_path", String.class);


	public static final LoomMetaKey<ThumbnailFlag> THUMBNAIL_FLAG_KEY = metaKey("thumbnail_flags", 1, XATTR, ThumbnailFlag.class);

	public static final LoomMetaKey<MetaDataStream> THUMBNAIL_BIN_KEY = metaKey("thumbnail_bin", 1, FS, MetaDataStream.class);

	
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
			Boolean isComplete = ctx.upstreamOutput("consistency", "is_complete");
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
			try (VideoFile video = Videos.open(path)) {
				Path thumbnailPath = resolveThumbnailPath(media);
				Files.createDirectories(thumbnailPath.getParent());
				try (OutputStream os = new FileOutputStream(thumbnailPath.toFile())) {
					gen.save(video, os);
					ctx.print("DONE", "");
					ctx.output(OUTPUT_THUMBNAIL_FLAG, "DONE");
					ctx.output(OUTPUT_THUMBNAIL_PATH, thumbnailPath.toString());
				}
			}
			Path thumbnailPath = resolveThumbnailPath(media);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to compute thumbnail", e);
			ctx.output(OUTPUT_THUMBNAIL_FLAG, "FAILED");
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
