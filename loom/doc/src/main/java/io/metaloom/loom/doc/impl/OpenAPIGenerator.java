package io.metaloom.loom.doc.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.io.FileUtils;

import io.metaloom.loom.agent.chat.rest.ChatSessionEndpoint;
import io.metaloom.loom.agent.chat.rest.ChatStreamEndpoint;
import io.metaloom.loom.agent.chat.rest.SessionFsEndpoint;
import io.metaloom.loom.agent.memory.rest.MemoryDenyRuleEndpoint;
import io.metaloom.loom.agent.memory.rest.MemoryDenyRuleEndpointService;
import io.metaloom.loom.agent.memory.rest.MemoryEndpoint;
import io.metaloom.loom.agent.memory.rest.MemoryEndpointService;
import io.metaloom.loom.doc.Generator;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;
import io.metaloom.loom.rest.openapi.LoomOpenAPI;

/**
 * Writes the OpenAPI document of the Loom REST API to {@code src/main/generated/openapi.json} and {@code openapi.yaml}.
 *
 * <p>
 * Both files are staged into the website (`website/static/docs/examples/`) where the docs page offers them for download and renders them in the
 * embedded API explorer.
 */
public class OpenAPIGenerator implements Generator {

	@Override
	public void generate() throws IOException {
		// The chat and memory endpoints live in the agent modules which depend on the rest
		// module - they can only be added from here, where both are on the classpath.
		LoomOpenAPI api = new LoomOpenAPI(OpenAPIGenerator::agentEndpoints);

		File jsonFile = new File("src/main/generated/openapi.json");
		FileUtils.writeStringToFile(jsonFile, api.generateJson(), StandardCharsets.UTF_8, false);

		File yamlFile = new File("src/main/generated/openapi.yaml");
		FileUtils.writeStringToFile(yamlFile, api.generateYaml(), StandardCharsets.UTF_8, false);
	}

	/**
	 * The endpoints are constructed with null services on purpose - only {@code register()} is called on them and that never dereferences a service.
	 *
	 * @param deps
	 *            dependencies carrying the router the spec is generated from
	 * @return
	 */
	private static List<RESTEndpoint> agentEndpoints(EndpointDependencies deps) {
		// The memory endpoints bind their routes with `service::method` references, so unlike
		// the others they need an actual (if hollow) service instance to register at all.
		return List.of(
			new ChatStreamEndpoint(null, deps),
			new ChatSessionEndpoint(null, deps),
			new SessionFsEndpoint(null, deps),
			new MemoryEndpoint(new MemoryEndpointService(null, null, null), deps),
			new MemoryDenyRuleEndpoint(new MemoryDenyRuleEndpointService(null, null, null, null), deps));
	}

}
