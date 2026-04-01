package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.CollectionEndpointService;
import io.metaloom.loom.rest.service.impl.EmbeddingEndpointService;

public class EmbeddingEndpoint extends AbstractEndpoint{

	private static final Logger log = LoggerFactory.getLogger(CollectionEndpointService.class);
	
	private final ModelExamples examples;
	private final EmbeddingEndpointService service;

	@Inject
	public EmbeddingEndpoint(EmbeddingEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.examples = examples;
		this.service = service;
	}

	@Override
	public String name() {
		return "embedding";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/embeddings";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create new embedding",
			examples.embeddingCreateRequestExample(),
			examples.embeddingResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a embedding",
			examples.embeddingUpdateRequestExample(),
			examples.embeddingResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a embedding",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of embeddings",
			examples.embeddingListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a embedding",
			null,
			examples.embeddingResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:embeddingUuid/attachments", POST, "Create a new attachment for the embedding", lrc -> {
			service.createEmbeddingAttachment(lrc.pathParamUUID("embeddingUuid"));
		});
		addRoute(basePath() + "/:embeddingUuid/attachments", GET, "List the attachments for the embedding", lrc -> {
			service.listEmbeddingAttachments(lrc.pathParamUUID("embeddingUuid"));
		});

	}

}
