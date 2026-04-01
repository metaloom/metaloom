package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.comment.CommentListResponse;
import io.metaloom.loom.rest.model.comment.CommentResponse;

public interface CommentModelBuilder extends ModelBuilder, UserModelBuilder {

	default CommentResponse toResponse(Comment comment) {
		CommentResponse response = new CommentResponse();
		response.setTitle(comment.getTitle());
		response.setUuid(comment.getUuid());
		setStatus(comment, response);
		return response;
	}

	default CommentListResponse toCommentList(Page<Comment> page) {
		return setPage(new CommentListResponse(), page, this::toResponse);
	}
}
