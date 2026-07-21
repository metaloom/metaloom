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
import io.metaloom.loom.rest.service.impl.TaskEndpointService;

public class TaskEndpoint extends AbstractEndpoint  {

	private static final Logger log = LoggerFactory.getLogger(TaskEndpoint.class);

	private final TaskEndpointService service;
	private final ReactionEndpointService reactionService;
	private final CommentEndpointService commentService;
	private final ModelExamples examples;


	@Inject
	public TaskEndpoint(TaskEndpointService service, ReactionEndpointService reactionService, CommentEndpointService commentService,
		EndpointDependencies deps, ModelExamples examples) {
		super( deps);
		this.service = service;
		this.reactionService = reactionService;
		this.commentService = commentService;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "task";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/tasks";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create new task",
			examples.taskCreateRequestExample(),
			examples.taskResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a task",
			examples.taskUpdateRequestExample(),
			examples.taskResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a task",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of tasks",
			examples.taskListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a task",
			null,
			examples.taskResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// REACTION

		addRoute(basePath() + "/:taskUuid/reactions", POST, "Create a new reaction for a task", lrc -> {
			reactionService.createTaskReaction(lrc, lrc.pathParamUUID("taskUuid"));
		});

		addRoute(basePath() + "/:taskUuid/reactions/:reactionUuid", DELETE, "Delete a reaction for a task",lrc -> {
			reactionService.deleteTaskReaction(lrc, lrc.pathParamUUID("taskUuid"), lrc.pathParamUUID("reactionUuid"));
		});

		addRoute(basePath() + "/:taskUuid/reactions", GET, "Load a paged list of reactions for a task",  lrc -> {
			reactionService.listTaskReactions(lrc, lrc.pathParamUUID("taskUuid"));
		});

		addRoute(basePath() + "/:taskUuid/reactions/:reactionUuid", GET, "Return a specific reaction for a task", lrc -> {
			reactionService.loadTaskReaction(lrc, lrc.pathParamUUID("taskUuid"), lrc.pathParamUUID("reactionUuid"));
		});

		addRoute(basePath() + "/:taskUuid/reactions/:reactionUuid", POST, "Update a reaction for a task", lrc -> {
			reactionService.updateTaskReaction(lrc, lrc.pathParamUUID("taskUuid"), lrc.pathParamUUID("reactionUuid"));
		});

		// COMMENT

		addRoute(basePath() + "/:taskUuid/comments", POST,
			"Create a new comment on a task",
			examples.commentCreateRequestExample(),
			examples.commentResponseExample(),
			lrc -> {
				commentService.createForTask(lrc, lrc.pathParamUUID("taskUuid"));
			});

		addRoute(basePath() + "/:taskUuid/comments", GET,
			"List the comments on a task",
			null,
			examples.commentListResponseExample(),
			lrc -> {
				commentService.listForTask(lrc, lrc.pathParamUUID("taskUuid"));
			});

	}
}
