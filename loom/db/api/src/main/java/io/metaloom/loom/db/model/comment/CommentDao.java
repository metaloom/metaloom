package io.metaloom.loom.db.model.comment;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface CommentDao extends CRUDDao<Comment> {

	default Comment createComment(User user, String title, String text) {
		return createComment(user.getUuid(), title, text);
	}

	default Comment createComment(UUID userUuid, String title, String text) {
		return createComment(userUuid, null, title, text);
	}

	Comment createComment(UUID userUuid, UUID assetUuid, String title, String text);

	Comment createCommentForTask(UUID userUuid, UUID taskUuid, String title, String text);

	List<Comment> loadForTask(UUID taskUuid);

	List<Comment> loadForAsset(UUID assetUuid);

}
