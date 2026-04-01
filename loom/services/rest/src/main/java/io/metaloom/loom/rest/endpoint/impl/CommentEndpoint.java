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
import io.metaloom.loom.rest.service.impl.CommentEndpointService;
import io.metaloom.loom.rest.service.impl.ReactionEndpointService;

public class CommentEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(CommentEndpoint.class);

	private final ReactionEndpointService reactionService;
	private final CommentEndpointService service;
	private final ModelExamples examples;

	@Inject
	public CommentEndpoint(CommentEndpointService service, ReactionEndpointService reactionService, EndpointDependencies deps,
		ModelExamples examples) {
		super(deps);
		this.service = service;
		this.reactionService = reactionService;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "comment";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/comments";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create new comment",
			examples.commentCreateRequestExample(),
			examples.commentResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a comment",
			examples.commentUpdateRequestExample(),
			examples.commentResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a comment",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of comments",
			examples.commentListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a comment",
			null,
			examples.commentResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// REACTION

		addRoute(basePath() + "/:commentUuid/reactions", POST, "Create a reaction on a comment", lrc -> {
			reactionService.createCommentReaction(lrc, lrc.pathParamUUID("commentUuid"));
		});

		addRoute(basePath() + "/:commentUuid/reactions/:reactionUuid", DELETE, "Delete the commment for the reaction", lrc -> {
			reactionService.deleteCommentReaction(lrc, lrc.pathParamUUID("commentUuid"), lrc.pathParamUUID("reactionUuid"));
		});

		addRoute(basePath() + "/:commentUuid/reactions", GET, "Load a paged list for reactions on a specific comment", lrc -> {
			reactionService.listCommentReactions(lrc, lrc.pathParamUUID("commentUuid"));
		});

		addRoute(basePath() + "/:commentUuid/reactions/:reactionUuid", GET, "Load a specific reaction for a comment", lrc -> {
			reactionService.loadCommentReaction(lrc, lrc.pathParamUUID("commentUuid"), lrc.pathParamUUID("reactionUuid"));
		});

		addRoute(basePath() + "/:commentUuid/reactions/:reactionUuid", POST, "Update a specific reaction on a comment", lrc -> {
			reactionService.updateCommentReaction(lrc, lrc.pathParamUUID("commentUuid"), lrc.pathParamUUID("reactionUuid"));
		});
	}

}
