package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.comment.CommentCreateRequest;
import io.metaloom.loom.rest.model.comment.CommentListResponse;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.comment.CommentUpdateRequest;

public interface CommentMethods {

	LoomClientRequest<CommentResponse> loadComment(UUID commentUuid);

	LoomClientRequest<CommentResponse> createComment(CommentCreateRequest request);

	LoomClientRequest<CommentResponse> updateComment(UUID commentUuid, CommentUpdateRequest request);

	LoomClientRequest<CommentListResponse> listComments();

	LoomClientRequest<CommentListResponse> listCommentsForAnnotation(UUID annotationUuid);

	LoomClientRequest<CommentResponse> createAnnotationComment(UUID annotationUuid, CommentCreateRequest request);

	LoomClientRequest<CommentResponse> createTaskComment(UUID taskUuid, CommentCreateRequest request);

	LoomClientRequest<CommentListResponse> listTaskComments(UUID taskUuid);

	LoomClientRequest<NoResponse> deleteComment(UUID commentUuid);

}
