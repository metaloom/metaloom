package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.comment.CommentListResponse;

public class CommentModelBuilderTest extends AbstractModelBuilderTest {

	private static final UUID COMMENT_UUID = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");

	@Test
	@Override
	void testResponseModel() throws IOException {
		Comment comment = mockComment("title_0", "text_0");
		assertWithModel(builder().toResponse(comment), "comment.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Comment comment1 = mockComment("title_0", "text_0");
		Comment comment2 = mockComment("title_1", "text_1");
		Page<Comment> page = mockPage(comment1, comment2);
		CommentListResponse list = builder().toCommentList(page);
		assertWithModel(list, "comment.list_response");
	}

	private Comment mockComment(String title, String text) {
		Comment comment = mock(Comment.class);
		when(comment.getUuid()).thenReturn(COMMENT_UUID);
		when(comment.getTitle()).thenReturn(title);
		when(comment.getText()).thenReturn(text);
		when(comment.getAssetUuid()).thenReturn(ASSET_UUID);
		mockCreatorEditor(comment);
		return comment;
	}

}
