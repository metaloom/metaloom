package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.comment.CommentCreateRequest;
import io.metaloom.loom.rest.model.comment.CommentListResponse;
import io.metaloom.loom.rest.model.comment.CommentResponse;

/**
 * Covers the annotation comment sub-resource (`/api/v1/annotations/:annotationUuid/comments`).
 *
 * The client previously requested the singular `annotation/:uuid/comments`, a path the server never registered — these tests pin the
 * corrected contract down on both sides.
 */
public class AnnotationCommentEndpointTest extends AbstractEndpointTest {

	@Test
	public void testCommentOnAnnotation() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			CommentCreateRequest request = new CommentCreateRequest();
			request.setTitle("Annotation feedback");
			request.setText("The highlighted area is off by a few frames");
			CommentResponse comment = client.createAnnotationComment(ANNOTATION_UUID, request).sync().body();
			assertThat(comment).isValid();

			CommentListResponse list = client.listCommentsForAnnotation(ANNOTATION_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(list.getData().stream().anyMatch(c -> c.getUuid().equals(comment.getUuid())))
				.as("The created comment must be listed on the annotation").isTrue();
		}
	}

	@Test
	public void testAnnotationScoping() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			CommentCreateRequest request = new CommentCreateRequest();
			request.setTitle("Scoped");
			request.setText("Belongs to one annotation only");
			CommentResponse comment = client.createAnnotationComment(ANNOTATION_UUID, request).sync().body();
			assertThat(comment).isValid();

			// A comment created without an annotation scope must not leak into the annotation listing.
			CommentCreateRequest unscoped = new CommentCreateRequest();
			unscoped.setTitle("Unscoped");
			unscoped.setText("Belongs to no annotation");
			CommentResponse globalComment = client.createComment(unscoped).sync().body();
			assertThat(globalComment).isValid();

			CommentListResponse list = client.listCommentsForAnnotation(ANNOTATION_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(list.getData().stream().noneMatch(c -> c.getUuid().equals(globalComment.getUuid())))
				.as("An unscoped comment must not be listed on the annotation").isTrue();

			// An unrelated annotation must not see the comment either.
			CommentListResponse other = client.listCommentsForAnnotation(UUID.randomUUID()).sync().body();
			org.assertj.core.api.Assertions.assertThat(other.getData() == null
				|| other.getData().stream().noneMatch(c -> c.getUuid().equals(comment.getUuid())))
				.as("A different annotation must not list the comment").isTrue();
		}
	}

	@Test
	public void testCommentPermissions() throws LoomClientException {
		// checkPerm runs before the DAO loader, so a permissionless user is rejected with 403 regardless of the target.
		try (LoomHttpClient client = loginPermissionlessClient()) {
			CommentCreateRequest request = new CommentCreateRequest();
			request.setTitle("Denied");
			request.setText("Should never be stored");
			expect(403, "Forbidden", client.createAnnotationComment(ANNOTATION_UUID, request));
			expect(403, "Forbidden", client.listCommentsForAnnotation(ANNOTATION_UUID));
		}
	}

}
