package io.metaloom.cortex.pipeline.loader;

import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.vertx.core.json.JsonObject;

/**
 * Factory interface for creating concrete {@link PipelineNode} implementations
 * from JSON node definitions loaded from the Loom server.
 * <p>
 * Implementations should inspect the node definition (e.g., the {@code id} or {@code type} field)
 * and return the appropriate processing node. Return {@code null} if a node type is not recognized,
 * in which case a stub node will be used.
 */
public interface NodeFactory {

	/**
	 * Create a {@link PipelineNode} from a JSON node definition.
	 *
	 * @param nodeDef the JSON node definition from the pipeline's definition
	 * @return a concrete PipelineNode, or null if not recognized
	 */
	PipelineNode createNode(JsonObject nodeDef);

	/**
	 * @return the node kinds this worker can execute; the ground truth behind what it
	 *         announces to Loom
	 */
	java.util.Set<String> registeredTypes();

}
