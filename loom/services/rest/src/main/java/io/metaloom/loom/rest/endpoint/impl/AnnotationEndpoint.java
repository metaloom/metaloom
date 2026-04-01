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
import io.metaloom.loom.rest.service.impl.AnnotationEndpointService;
import io.metaloom.loom.rest.service.impl.ReactionEndpointService;

public class AnnotationEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(AnnotationEndpoint.class);

	private final ReactionEndpointService reactionService;

	private final AnnotationEndpointService service;
	private final ModelExamples examples;

	@Inject
	public AnnotationEndpoint(AnnotationEndpointService service, ReactionEndpointService reactionService, EndpointDependencies deps,
		ModelExamples examples) {
		super(deps);
		this.service = service;
		this.reactionService = reactionService;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "annotation";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/annotations";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create new annotation",
			examples.annotationCreateRequestExample(),
			examples.annotationResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a annotation",
			examples.annotationUpdateRequestExample(),
			examples.annotationResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a annotation",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of annotations",
			examples.annotationListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a annotation",
			null,
			examples.annotationResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// REACTION

		addRoute(basePath() + "/:annotationUuid/reactions", POST, "Create a new reaction for an annotation", lrc -> {
			reactionService.createAnnotationReaction(lrc, lrc.pathParamUUID("annotationUuid"));
		});

		addRoute(basePath() + "/:annotationUuid/reactions/:reactionUuid", DELETE, "Delete a reaction for an annotation", lrc -> {
			reactionService.deleteAnnotationReaction(lrc, lrc.pathParamUUID("annotationUuid"), lrc.pathParamUUID("reactionUuid"));
		});

		addRoute(basePath() + "/:annotationUuid/reactions", GET, "Load a paged list of reactions on the annotation", lrc -> {
			reactionService.listAnnotationReactions(lrc, lrc.pathParamUUID("annotationUuid"));
		});

		addRoute(basePath() + "/:annotationUuid/reactions/:reactionUuid", GET, "Load the reaction for the annotation", lrc -> {
			reactionService.loadAnnotationReaction(lrc, lrc.pathParamUUID("annotationUuid"), lrc.pathParamUUID("reactionUuid"));
		});

		addRoute(basePath() + "/:annotationUuid/reactions/:reactionUuid", POST, "Update the reaction on the annotation", lrc -> {
			reactionService.updateAnnotationReaction(lrc, lrc.pathParamUUID("annotationUuid"), lrc.pathParamUUID("reactionUuid"));
		});
	}

}
