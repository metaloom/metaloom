package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_COMMENT;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_COMMENT;
import static io.metaloom.loom.db.model.perm.Permission.READ_COMMENT;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_COMMENT;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.comment.CommentDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.rest.model.comment.CommentCreateRequest;
import io.metaloom.loom.rest.model.comment.CommentListResponse;
import io.metaloom.loom.rest.model.comment.CommentModel;
import io.metaloom.loom.rest.model.comment.CommentUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class CommentEndpointService extends AbstractCRUDEndpointService<CommentDao, Comment> {

	private final NotificationDispatcher notifications;

	@Inject
	public CommentEndpointService(CommentDao commentDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		NotificationDispatcher notifications) {
		super(commentDao, daos, modelBuilder, validator);
		this.notifications = notifications;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_COMMENT, uuid);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_COMMENT, modelBuilder::toCommentList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_COMMENT, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_COMMENT, () -> {
			CommentCreateRequest request = lrc.requestBody(CommentCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String title = request.getTitle();
			String text = request.getText();
			Comment comment = dao().createComment(userUuid, title, text);
			applyParent(request, comment, null, null, null);
			update(request, comment);
			return comment;
		}, modelBuilder::toResponse, comment -> notifyReply(lrc, comment));
	}

	public void createForAsset(LoomRoutingContext lrc, UUID assetUuid) {
		create(lrc, CREATE_COMMENT, () -> {
			CommentCreateRequest request = lrc.requestBody(CommentCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String title = request.getTitle();
			String text = request.getText();
			Comment comment = dao().createComment(userUuid, assetUuid, title, text);
			applyParent(request, comment, assetUuid, null, null);
			update(request, comment);
			return comment;
		}, modelBuilder::toResponse, comment -> notifyReply(lrc, comment));
	}

	public void listForAsset(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_COMMENT, () -> {
			List<Comment> comments = dao().loadForAsset(assetUuid);
			CommentListResponse response = new CommentListResponse();
			for (Comment comment : comments) {
				response.add(modelBuilder.toResponse(comment));
			}
			lrc.send(response);
		});
	}

	public void createForAnnotation(LoomRoutingContext lrc, UUID annotationUuid) {
		create(lrc, CREATE_COMMENT, () -> {
			CommentCreateRequest request = lrc.requestBody(CommentCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String title = request.getTitle();
			String text = request.getText();
			Comment comment = dao().createCommentForAnnotation(userUuid, annotationUuid, title, text);
			applyParent(request, comment, null, annotationUuid, null);
			update(request, comment);
			return comment;
		}, modelBuilder::toResponse, comment -> notifyReply(lrc, comment));
	}

	public void listForAnnotation(LoomRoutingContext lrc, UUID annotationUuid) {
		checkPerm(lrc, READ_COMMENT, () -> {
			List<Comment> comments = dao().loadForAnnotation(annotationUuid);
			CommentListResponse response = new CommentListResponse();
			for (Comment comment : comments) {
				response.add(modelBuilder.toResponse(comment));
			}
			lrc.send(response);
		});
	}

	public void createForTask(LoomRoutingContext lrc, UUID taskUuid) {
		create(lrc, CREATE_COMMENT, () -> {
			CommentCreateRequest request = lrc.requestBody(CommentCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String title = request.getTitle();
			String text = request.getText();
			Comment comment = dao().createCommentForTask(userUuid, taskUuid, title, text);
			applyParent(request, comment, null, null, taskUuid);
			update(request, comment);
			return comment;
		}, modelBuilder::toResponse, comment -> notifyComment(lrc, comment, taskUuid));
	}

	public void listForTask(LoomRoutingContext lrc, UUID taskUuid) {
		checkPerm(lrc, READ_COMMENT, () -> {
			List<Comment> comments = dao().loadForTask(taskUuid);
			CommentListResponse response = new CommentListResponse();
			for (Comment comment : comments) {
				response.add(modelBuilder.toResponse(comment));
			}
			lrc.send(response);
		});
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_COMMENT, () -> {
			CommentUpdateRequest request = lrc.requestBody(CommentUpdateRequest.class);
			validator.validate(request);

			Comment comment = dao().load(uuid);
			update(request, comment);
			return comment;
		}, modelBuilder::toResponse);
	}

	private void update(CommentModel<?> model, Comment comment) {
		update(model::getTitle, comment::setTitle);
		update(model::getText, comment::setText);
		update(model::getMeta, comment::setMeta);
	}

	/**
	 * Resolve and attach the parent of a reply.
	 *
	 * <p>
	 * The parent must exist (404) and must hang off the <b>same</b> subject as the reply (400). Without the second check a reply could smuggle itself
	 * onto another task's or asset's thread simply by naming a parent from there — it would then render inside a conversation its author never had
	 * access to. A parent whose own subject is null (a bare {@code /comments} root) is accepted anywhere, because there is nothing to disagree with.
	 * </p>
	 */
	private void applyParent(CommentCreateRequest request, Comment comment, UUID assetUuid, UUID annotationUuid, UUID taskUuid) {
		UUID parentUuid = request.getParentUuid();
		if (parentUuid == null) {
			return;
		}
		Comment parent = dao().load(parentUuid);
		if (parent == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Parent comment not found " + parentUuid);
		}
		if (!sameSubject(parent.getAssetUuid(), assetUuid)
			|| !sameSubject(parent.getAnnotationUuid(), annotationUuid)
			|| !sameSubject(parent.getTaskUuid(), taskUuid)) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"The parent comment belongs to a different subject than this reply");
		}
		comment.setParentUuid(parentUuid);
	}

	private static boolean sameSubject(UUID parentSubject, UUID replySubject) {
		// A null on the PARENT side is permissive; a mismatch between two set values is not.
		return parentSubject == null || parentSubject.equals(replySubject);
	}

	/**
	 * Notify the people who care about a new comment on a task: everyone responsible for it and whoever raised it, plus — when the comment is a
	 * reply — the author of the parent.
	 *
	 * <p>
	 * Runs in the {@code afterStore} hook rather than the supplier, because a comment has no uuid until it is written and the notification has to
	 * deep-link to it.
	 * </p>
	 */
	private void notifyComment(LoomRoutingContext lrc, Comment comment, UUID taskUuid) {
		Task task = daos().taskDao().load(taskUuid);
		if (task != null) {
			notifications.taskCommented(lrc.userUuid(), task, comment);
		}
		notifyReply(lrc, comment);
	}

	/**
	 * Tell the author of the parent comment that somebody replied. A no-op for a root comment.
	 */
	private void notifyReply(LoomRoutingContext lrc, Comment comment) {
		if (comment.getParentUuid() == null) {
			return;
		}
		Comment parent = dao().load(comment.getParentUuid());
		if (parent != null) {
			notifications.commentReplied(lrc.userUuid(), parent, comment);
		}
	}
}
