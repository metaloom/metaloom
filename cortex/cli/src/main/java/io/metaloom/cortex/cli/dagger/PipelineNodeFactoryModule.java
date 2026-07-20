package io.metaloom.cortex.cli.dagger;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Module;
import dagger.Provides;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.node.source.fs.FilesystemSourceNode;
import io.metaloom.cortex.node.source.fs.FilesystemSourceNodeOptions;
import io.metaloom.cortex.node.hash.ChunkHashNode;
import io.metaloom.cortex.node.hash.MD5Node;
import io.metaloom.cortex.node.hash.SHA256Node;
import io.metaloom.cortex.node.hash.SHA512Node;
import io.metaloom.cortex.node.thumbnail.ThumbnailNode;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.core.node.AssetSourceNode;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.loader.NodeFactory;
import io.metaloom.cortex.pipeline.loader.RegistryNodeFactory;
import io.vertx.core.json.JsonArray;

/**
 * Dagger module that populates the {@link RegistryNodeFactory} with the
 * concrete cortex nodes that ship with the CLI.
 *
 * <p>Each registered producer wraps the legacy node in a
 * {@link CortexNodeAdapter} so it can participate in the modern pipeline
 * DAG. Additional cortex nodes can be registered here as they are wired
 * into {@link NodeCollectionModule}. Unknown node types continue to fall
 * back to stub nodes in the loader.</p>
 */
@Module(includes = NodeCollectionModule.class)
public class PipelineNodeFactoryModule {

	private static final Logger log = LoggerFactory.getLogger(PipelineNodeFactoryModule.class);

	@Provides
	@Singleton
	public static NodeFactory provideNodeFactory(
		SHA512Node sha512, SHA256Node sha256, MD5Node md5, ChunkHashNode chunkHash,
		ThumbnailNode thumbnail,
		LoomMediaLoader mediaLoader,
		FilesystemSourceNodeOptions fsSourceOptions,
		CortexOptions cortexOptions) {

		RegistryNodeFactory factory = new RegistryNodeFactory();

		// Source nodes are pipeline-level constructs rather than FilesystemNodes,
		// so they are constructed directly instead of via the CortexNodeAdapter.
		factory.register("filesystem-source", def -> filesystemSource(def, mediaLoader, fsSourceOptions, cortexOptions));

		// Asset source: run a pipeline against a single asset. Loom injects the asset's
		// stored path as the 'path' option when it dispatches an asset-scoped run.
		factory.register("asset-source", def -> assetSource(def, mediaLoader));

		// Register cortex nodes by the type strings we expect to see in
		// pipeline JSON. Multiple aliases per node are supported so pipeline
		// authors can use either the semantic name or the node's own name.
		factory.register("sha512", def -> adapt(sha512, def, cortexOptions));
		factory.register("sha256", def -> adapt(sha256, def, cortexOptions));
		factory.register("md5", def -> adapt(md5, def, cortexOptions));
		factory.register("chunk-hash", def -> adapt(chunkHash, def, cortexOptions));
		factory.register("thumbnail", def -> adapt(thumbnail, def, cortexOptions));

		log.info("Registered {} node producers with the pipeline node factory", factory.registeredTypes().size());
		return factory;
	}

	/**
	 * Build a {@code filesystem-source} node from its JSON definition. The
	 * selection may be given as a single {@code path} or as a {@code pathGlobs}
	 * array; when neither is present the node falls back to the configured
	 * {@link FilesystemSourceNodeOptions} defaults.
	 */
	private static PipelineNode filesystemSource(io.vertx.core.json.JsonObject nodeDef,
		LoomMediaLoader mediaLoader, FilesystemSourceNodeOptions defaults, CortexOptions cortexOptions) {

		String id = nodeDef.getString("id", FilesystemSourceNode.DEFAULT_ID);
		String path = nodeDef.getString("path");

		List<String> globs = readStringArray(nodeDef, "pathGlobs");
		List<String> emitStates = readStringArray(nodeDef, "emitStates");

		if (defaults != null) {
			ValidationResult result = defaults.validate();
			if (result.isInvalid()) {
				throw new IllegalStateException("Node '" + id + "' options validation failed: "
					+ String.join("; ", result.getErrors()));
			}
		}

		// Resolve the local base directory for the persisted per-root indexes:
		// an explicit indexPath option wins, otherwise derive it from the meta path.
		String configuredIndexPath = defaults != null ? defaults.getIndexPath() : null;
		java.nio.file.Path indexBaseDir;
		if (configuredIndexPath != null && !configuredIndexPath.isBlank()) {
			indexBaseDir = java.nio.file.Paths.get(configuredIndexPath).toAbsolutePath().normalize();
		} else if (cortexOptions != null && cortexOptions.getMetaPath() != null) {
			indexBaseDir = cortexOptions.getMetaPath().resolve("filesystem-index");
		} else {
			indexBaseDir = null;
		}

		return FilesystemSourceNode.create(id, mediaLoader, path, globs, emitStates, defaults, indexBaseDir);
	}

	/**
	 * Build an {@code asset-source} node from its JSON definition. The single asset it emits is identified by the {@code path} option, which Loom fills
	 * in from the asset's stored binary location when it dispatches an asset-scoped run.
	 */
	private static PipelineNode assetSource(io.vertx.core.json.JsonObject nodeDef, LoomMediaLoader mediaLoader) {
		String id = nodeDef.getString("id", "asset-source");
		String path = nodeDef.getString("path");
		if (path == null || path.isBlank()) {
			throw new IllegalStateException("Node '" + id + "' (asset-source) requires a 'path' option identifying the asset file");
		}
		LoomMedia media = mediaLoader.load(java.nio.file.Paths.get(path));
		return new AssetSourceNode(id, "Asset Source", media);
	}

	private static List<String> readStringArray(io.vertx.core.json.JsonObject nodeDef, String field) {
		List<String> values = new ArrayList<>();
		JsonArray array = nodeDef.getJsonArray(field);
		if (array != null) {
			for (int i = 0; i < array.size(); i++) {
				String value = array.getString(i);
				if (value != null && !value.isBlank()) {
					values.add(value);
				}
			}
		}
		return values;
	}

	private static PipelineNode adapt(io.metaloom.cortex.api.node.FilesystemNode<?, ?> wrapped, io.vertx.core.json.JsonObject nodeDef, CortexOptions cortexOptions) {
		String id = nodeDef.getString("id", wrapped.name());
		NodeMode mode = NodeMode.valueOf(nodeDef.getString("mode", "PARALLEL"));
		boolean blocking = nodeDef.getBoolean("blocking", true);
		int concurrency = nodeDef.getInteger("concurrency", 1);
		boolean syncToLoom = nodeDef.getBoolean("syncToLoom", false);
		
		// Validate concurrency
		if (concurrency <= 0) {
			throw new IllegalStateException("Node '" + id + "': concurrency must be positive, got " + concurrency);
		}
		
		// Use timeout from JSON if specified, otherwise fall back to default from config
		long timeoutMs = nodeDef.getLong("timeoutMs", 0L);
		if (timeoutMs == 0) {
			// Try to get default timeout from config based on node type
			String nodeType = nodeDef.getString("type", wrapped.name());
			timeoutMs = cortexOptions.getDefaultTimeoutMs(nodeType);
		}
		
		// Validate timeout
		if (timeoutMs < 0) {
			throw new IllegalStateException("Node '" + id + "': timeoutMs must be non-negative, got " + timeoutMs);
		}
		
		CortexNodeAdapter adapter = new CortexNodeAdapter(id, wrapped, mode, blocking, concurrency, timeoutMs);
		if (syncToLoom) {
			adapter.setSyncToLoom(true);
		}
		
		// Validate node options if available in cortexOptions
		if (cortexOptions != null && cortexOptions.getNodes() != null) {
			CortexNodeOptions nodeOptions = cortexOptions.getNodes().get(wrapped.name());
			if (nodeOptions != null) {
				ValidationResult result = nodeOptions.validate();
				if (result.isInvalid()) {
					throw new IllegalStateException("Node '" + id + "' options validation failed: " + String.join("; ", result.getErrors()));
				}
			}
		}
		
		return adapter;
	}
}
