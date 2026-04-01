package io.metaloom.loom.db.model.reaction;

import java.util.List;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface ReactionDao extends CRUDDao<Reaction> {

	default Reaction createReaction(User user, String type) {
		return createReaction(user.getUuid(), type);
	}

	Reaction createReaction(UUID userUuid, String type);

	// Asset

	Reaction loadAssetReaction(AssetId assetId, UUID reactionUuid);

	Page<Reaction> loadPageForAsset(AssetId assetId, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	Page<Reaction> loadPageForAnnotation(UUID annotationUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection);

	// Comment

	Page<Reaction> loadPageForComment(UUID commentUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	Reaction loadCommentReaction(UUID commentUuid, UUID reactionUuid);

	// Task

	Reaction loadTaskReaction(UUID taskUuid, UUID reactionUuid);

	Page<Reaction> loadPageForTask(UUID taskUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	// Annotation

	Reaction loadAnnotationReaction(UUID annotationUuid, UUID reactionUuid);
}
