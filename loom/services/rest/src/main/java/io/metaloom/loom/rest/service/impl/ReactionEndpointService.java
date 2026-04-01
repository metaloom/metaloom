package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_REACTION;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_REACTION;
import static io.metaloom.loom.db.model.perm.Permission.READ_REACTION;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_REACTION;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.reaction.Reaction;
import io.metaloom.loom.db.model.reaction.ReactionDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.reaction.ReactionCreateRequest;
import io.metaloom.loom.rest.model.reaction.ReactionListResponse;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.metaloom.loom.rest.parameter.FilterParameters;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.parameter.SortParameters;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class ReactionEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(ReactionEndpointService.class);

	private ReactionDao dao;

	private AssetDao assetDao;

	@Inject
	public ReactionEndpointService(ReactionDao reactionDao, AssetDao assetDao, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.dao = reactionDao;
		this.assetDao = assetDao;
	}

	public ReactionDao dao() {
		return dao;
	}

	protected void create(LoomRoutingContext lrc, Permission permission, ReactionCreator creator) {
		checkPerm(lrc, permission, () -> {
			ReactionCreateRequest request = lrc.requestBody(ReactionCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String type = request.getType().name();
			Reaction element = creator.createReaction(userUuid, type);
			dao().store(element);
			ReactionResponse response = modelBuilder.toResponse(element);
			lrc.send(response, 201);
		});
	}

	protected void delete(LoomRoutingContext lrc, Permission permission, Supplier<Reaction> loader) {
		checkPerm(lrc, permission, () -> {
			Reaction reaction = loader.get();
			if (reaction == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Reaction not found.");
			} else {
				dao().delete(reaction);
				lrc.sendNoContent();
			}
		});
	}

	protected void load(LoomRoutingContext lrc, Permission permission, Supplier<Reaction> loader) {
		checkPerm(lrc, permission, () -> {
			Reaction reaction = loader.get();
			if (reaction == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Reaction not found");
			}
			ReactionResponse response = modelBuilder.toResponse(reaction);
			lrc.send(response);
		});
	}

	protected void update(LoomRoutingContext lrc, Permission permission, Supplier<Reaction> loader) {
		checkPerm(lrc, permission, () -> {
			Reaction reaction = loader.get();
			dao().update(reaction);
			ReactionResponse response = modelBuilder.toResponse(reaction);
			lrc.send(response, 200);
		});
	}

	protected void list(LoomRoutingContext lrc, Permission permission, ReactionPageLoader loader) {
		checkPerm(lrc, permission, () -> {
			PagingParameters pagingParameters = lrc.pagingParams();
			FilterParameters filterParameters = lrc.filterParams();
			SortParameters sortParameters = lrc.sortParams();
			UUID from = pagingParameters.from();
			int limit = pagingParameters.limit();
			if (log.isDebugEnabled()) {
				log.debug("Loading page from {} limit: {}", from, limit);
			}
			Page<Reaction> page = loader.loadPage(from, limit, filterParameters.filters(), sortParameters.sortBy(), sortParameters.sortOrder());
			ReactionListResponse response = modelBuilder.toReactionList(page);
			lrc.send(response);
		});
	}

	// ASSET

	public void createAssetReaction(LoomRoutingContext lrc, AssetId assetId) {
		UUID assetUuid = null;
		if (assetId.isUUID()) {
			assetUuid = assetId.uuid();
		} else {
			Asset asset = assetDao.loadBySHA512(assetId.hashsum());
			assetUuid = asset.getUuid();
		}
		final UUID auid = assetUuid;
		create(lrc, CREATE_REACTION, (userUuid, type) -> dao().createReaction(userUuid, type).setAssetUuid(auid));
	}

	public void deleteAssetReaction(LoomRoutingContext lrc, AssetId assetId, UUID reactionUuid) {
		delete(lrc, DELETE_REACTION, () -> dao().loadAssetReaction(assetId, reactionUuid));
	}

	public void listAssetReactions(LoomRoutingContext lrc, AssetId assetId) {
		list(lrc, READ_REACTION,
			(fromId, pageSize, filters, sortBy, sortDirection) -> dao().loadPageForAsset(assetId, fromId, pageSize, filters, sortBy, sortDirection));
	}

	public void loadAssetReaction(LoomRoutingContext lrc, AssetId assetId, UUID reactionUuid) {
		load(lrc, READ_REACTION, () -> dao().loadAssetReaction(assetId, reactionUuid));
	}

	public void updateAssetReaction(LoomRoutingContext lrc, AssetId assetId, UUID reactionUuid) {
		update(lrc, UPDATE_REACTION, () -> dao().loadAssetReaction(assetId, reactionUuid));
	}

	// TASK

	public void createTaskReaction(LoomRoutingContext lrc, UUID taskUuid) {
		create(lrc, CREATE_REACTION, (userUuid, type) -> dao().createReaction(userUuid, type).setTaskUuid(taskUuid));
	}

	public void deleteTaskReaction(LoomRoutingContext lrc, UUID taskUuid, UUID reactionUuid) {
		delete(lrc, DELETE_REACTION, () -> dao().loadTaskReaction(taskUuid, reactionUuid));
	}

	public void listTaskReactions(LoomRoutingContext lrc, UUID taskUuid) {
		list(lrc, READ_REACTION,
			(fromId, pageSize, filters, sortBy, sortDirection) -> dao().loadPageForTask(taskUuid, fromId, pageSize, filters, sortBy, sortDirection));
	}

	public void loadTaskReaction(LoomRoutingContext lrc, UUID taskUuid, UUID reactionUuid) {
		load(lrc, READ_REACTION, () -> dao().loadTaskReaction(taskUuid, reactionUuid));
	}

	public void updateTaskReaction(LoomRoutingContext lrc, UUID taskUuid, UUID reactionUuid) {
		update(lrc, UPDATE_REACTION, () -> dao().loadTaskReaction(taskUuid, reactionUuid));
	}

	// COMMENT

	public void createCommentReaction(LoomRoutingContext lrc, UUID commentUuid) {
		create(lrc, CREATE_REACTION, (userUuid, type) -> dao().createReaction(userUuid, type).setCommentUuid(commentUuid));
	}

	public void deleteCommentReaction(LoomRoutingContext lrc, UUID commentUuid, UUID reactionUuid) {
		delete(lrc, DELETE_REACTION, () -> dao().loadCommentReaction(commentUuid, reactionUuid));
	}

	public void listCommentReactions(LoomRoutingContext lrc, UUID commentUuid) {
		list(lrc, READ_REACTION, (fromId, pageSize, filters, sortBy, sortDirection) -> dao().loadPageForComment(commentUuid, fromId, pageSize,
			filters, sortBy, sortDirection));
	}

	public void loadCommentReaction(LoomRoutingContext lrc, UUID commentUuid, UUID reactionUuid) {
		load(lrc, READ_REACTION, () -> dao().loadCommentReaction(commentUuid, reactionUuid));
	}

	public void updateCommentReaction(LoomRoutingContext lrc, UUID commentUuid, UUID reactionUuid) {
		update(lrc, UPDATE_REACTION, () -> dao().loadCommentReaction(commentUuid, reactionUuid));
	}

	// ANNOTATION

	public void createAnnotationReaction(LoomRoutingContext lrc, UUID annotationUuid) {
		create(lrc, CREATE_REACTION, (userUuid, type) -> dao().createReaction(userUuid, type).setAnnotationUuid(annotationUuid));
	}

	public void deleteAnnotationReaction(LoomRoutingContext lrc, UUID annotationUuid, UUID reactionUuid) {
		delete(lrc, DELETE_REACTION, () -> dao().loadAnnotationReaction(annotationUuid, reactionUuid));
	}

	public void listAnnotationReactions(LoomRoutingContext lrc, UUID annotationUuid) {
		list(lrc, READ_REACTION, (fromId, pageSize, filters, sortBy, sortDirection) -> dao().loadPageForAnnotation(annotationUuid, fromId, pageSize,
			filters, sortBy, sortDirection));
	}

	public void loadAnnotationReaction(LoomRoutingContext lrc, UUID annotationUuid, UUID reactionUuid) {
		load(lrc, READ_REACTION, () -> dao().loadAnnotationReaction(annotationUuid, reactionUuid));
	}

	public void updateAnnotationReaction(LoomRoutingContext lrc, UUID annotationUuid, UUID reactionUuid) {
		update(lrc, UPDATE_REACTION, () -> dao().loadAnnotationReaction(annotationUuid, reactionUuid));
	}

	@FunctionalInterface
	interface ReactionCreator {
		Reaction createReaction(UUID userUid, String type);
	}

	@FunctionalInterface
	interface ReactionPageLoader {
		Page<Reaction> loadPage(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);
	}
}
