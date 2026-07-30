package io.metaloom.loom.doc.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;

import org.apache.commons.io.FileUtils;

import io.metaloom.loom.doc.Generator;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeDescriptorProvider;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Writes the pipeline node-descriptor snapshot to {@code src/main/generated/node-descriptors.json}.
 *
 * <p>
 * The snapshot mirrors the combined response of {@code io.metaloom.loom.rest.endpoint.impl.NodeDescriptorEndpoint} — the shape
 * {@code {"nodeDescriptors":[…], "contentTypes":[…]}} — so it can be baked into the static website's in-browser pipeline editor,
 * which has no backend to query. It is staged into {@code website/static/pipeline-editor/node-descriptors.json} the same way the
 * OpenAPI/GraphQL documents are staged (a manual copy — nothing does it automatically).
 *
 * <p>
 * Registry population is identical to {@code RESTModule.nodeDescriptorRegistry()}: every {@link NodeDescriptorProvider} discovered
 * via {@link ServiceLoader} contributes its descriptors.
 */
public class NodeDescriptorGenerator implements Generator {

	@Override
	public void generate() throws IOException {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		ServiceLoader.load(NodeDescriptorProvider.class)
			.forEach(provider -> provider.getDescriptors().forEach(registry::register));

		// Encode via Vert.x Json, exactly like the endpoint, so field names match byte-for-byte
		// (inputPorts/outputPorts/id/cardinality/…), then pretty-print the combined object.
		JsonObject snapshot = new JsonObject()
			.put("nodeDescriptors", new JsonArray(Json.encode(registry.getAll())))
			.put("contentTypes", new JsonArray(Json.encode(ContentTypeRegistry.all())));

		File out = new File("src/main/generated/node-descriptors.json");
		FileUtils.writeStringToFile(out, snapshot.encodePrettily() + "\n", StandardCharsets.UTF_8, false);
	}
}
