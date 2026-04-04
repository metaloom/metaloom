package io.metaloom.cortex.pipeline.loader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineManager;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.filter.MediaFilter;
import io.metaloom.cortex.pipeline.api.filter.PipelineFilter;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Loads pipeline definitions from the Loom server and registers them
 * with the local {@link PipelineManager}.
 * <p>
 * The pipeline's {@code definition} JSON is expected to have the following structure:
 * <pre>
 * {
 *   "filters": {
 *     "mimeTypes": ["video/*", "image/*"],
 *     "pathGlobs": ["**&#47;*.mp4"]
 *   },
 *   "nodes": [
 *     {
 *       "id": "sha512",
 *       "name": "SHA-512 Hash",
 *       "mode": "PARALLEL",
 *       "blocking": true,
 *       "dependencies": [],
 *       "concurrency": 4,
 *       "syncToLoom": true,
 *       "options": {}
 *     }
 *   ]
 * }
 * </pre>
 * Nodes loaded from the server are stub nodes that carry configuration only.
 * A {@link NodeFactory} can be provided to resolve each node definition to a
 * concrete processing implementation.
 */
@Singleton
public class LoomPipelineLoader {

	private static final Logger log = LoggerFactory.getLogger(LoomPipelineLoader.class);

	private final LoomClient loomClient;
	private final PipelineManager pipelineManager;
	private NodeFactory nodeFactory;

	@Inject
	public LoomPipelineLoader(@Nullable LoomClient loomClient, PipelineManager pipelineManager) {
		this.loomClient = loomClient;
		this.pipelineManager = pipelineManager;
	}

	/**
	 * Set a factory to resolve node definitions into concrete PipelineNode implementations.
	 * If not set, stub nodes that log and return success are used.
	 */
	public void setNodeFactory(NodeFactory nodeFactory) {
		this.nodeFactory = nodeFactory;
	}

	/**
	 * Load all pipelines from the Loom server and register them with the pipeline manager.
	 *
	 * @return the number of pipelines loaded
	 */
	public int loadAndRegister() {
		if (loomClient == null) {
			log.warn("No Loom client available. Skipping pipeline loading.");
			return 0;
		}

		try {
			LoomClientRequest<PipelineListResponse> request = loomClient.listPipelines();
			PipelineListResponse response = request.sync();
			if (response == null || response.getData() == null) {
				log.info("No pipelines found on Loom server.");
				return 0;
			}

			int count = 0;
			for (PipelineResponse pr : response.getData()) {
				try {
					Pipeline pipeline = toPipeline(pr);
					pipelineManager.register(pipeline);
					log.info("Loaded pipeline '{}' (priority={}, enabled={})", pipeline.name(), pipeline.priority(), pipeline.isEnabled());
					count++;
				} catch (Exception e) {
					log.error("Failed to load pipeline '{}': {}", pr.getName(), e.getMessage(), e);
				}
			}
			log.info("Loaded {} pipelines from Loom server.", count);
			return count;
		} catch (Exception e) {
			log.error("Failed to load pipelines from Loom server: {}", e.getMessage(), e);
			return 0;
		}
	}

	/**
	 * Convert a {@link PipelineResponse} into a Cortex {@link Pipeline}.
	 */
	Pipeline toPipeline(PipelineResponse response) {
		JsonObject definition = response.getDefinition();

		DefaultPipeline.Builder builder = DefaultPipeline.builder(response.getName())
			.description(response.getDescription() != null ? response.getDescription() : "")
			.priority(response.getPriority() != null ? response.getPriority() : 0)
			.enabled(response.isEnabled() != null ? response.isEnabled() : true)
			.dryRun(response.isDryRun() != null ? response.isDryRun() : false);

		// Parse filters
		if (definition != null && definition.containsKey("filters")) {
			PipelineFilter filter = parseFilter(definition.getJsonObject("filters"));
			if (filter != null) {
				builder.filter(filter);
			}
		}

		// Parse nodes
		if (definition != null && definition.containsKey("nodes")) {
			JsonArray nodesArray = definition.getJsonArray("nodes");
			for (int i = 0; i < nodesArray.size(); i++) {
				JsonObject nodeDef = nodesArray.getJsonObject(i);
				PipelineNode node = parseNode(nodeDef);
				if (node != null) {
					builder.addNode(node);
				}
			}
		}

		return builder.build();
	}

	private PipelineFilter parseFilter(JsonObject filterDef) {
		if (filterDef == null) {
			return null;
		}

		Set<String> mimeTypes = new HashSet<>();
		JsonArray mimeArray = filterDef.getJsonArray("mimeTypes");
		if (mimeArray != null) {
			for (int i = 0; i < mimeArray.size(); i++) {
				mimeTypes.add(mimeArray.getString(i));
			}
		}

		List<String> pathGlobs = new ArrayList<>();
		JsonArray globArray = filterDef.getJsonArray("pathGlobs");
		if (globArray != null) {
			for (int i = 0; i < globArray.size(); i++) {
				pathGlobs.add(globArray.getString(i));
			}
		}

		return new MediaFilter(mimeTypes, pathGlobs);
	}

	@SuppressWarnings("unchecked")
	private PipelineNode parseNode(JsonObject nodeDef) {
		String id = nodeDef.getString("id");
		if (id == null) {
			log.warn("Skipping node definition without id: {}", nodeDef);
			return null;
		}

		// If a custom factory is set, delegate to it
		if (nodeFactory != null) {
			PipelineNode resolved = nodeFactory.createNode(nodeDef);
			if (resolved != null) {
				return resolved;
			}
			log.warn("NodeFactory returned null for node '{}'. Using stub.", id);
		}

		String name = nodeDef.getString("name", id);
		NodeMode mode = NodeMode.valueOf(nodeDef.getString("mode", "PARALLEL"));
		boolean blocking = nodeDef.getBoolean("blocking", true);
		int concurrency = nodeDef.getInteger("concurrency", 1);
		boolean syncToLoom = nodeDef.getBoolean("syncToLoom", false);

		Set<String> dependencies = new HashSet<>();
		JsonArray depsArray = nodeDef.getJsonArray("dependencies");
		if (depsArray != null) {
			for (int i = 0; i < depsArray.size(); i++) {
				dependencies.add(depsArray.getString(i));
			}
		}

		// Return a stub node that carries configuration but just logs
		return new StubPipelineNode(id, name, mode, blocking, dependencies, concurrency, syncToLoom);
	}

	/**
	 * A placeholder node that carries configuration loaded from the server.
	 * Actual processing should be handled by replacing this with a real node via {@link NodeFactory}.
	 */
	private static class StubPipelineNode extends AbstractPipelineNode {

		StubPipelineNode(String id, String name, NodeMode mode, boolean blocking,
				Set<String> dependencies, int concurrency, boolean syncToLoom) {
			super(id, name, mode, blocking, dependencies, concurrency, syncToLoom);
		}

		@Override
		public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			long start = System.currentTimeMillis();
			log.debug("Stub node '{}' processing media: {}", id(), media.absolutePath());
			long duration = System.currentTimeMillis() - start;
			return NodeResult.success(id(), duration);
		}

		private static final Logger log = LoggerFactory.getLogger(StubPipelineNode.class);
	}
}
