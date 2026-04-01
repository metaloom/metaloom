package io.metaloom.loom.client.common.method;

import static io.metaloom.loom.api.asset.AssetId.assetId;

import java.util.UUID;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.reaction.ReactionCreateRequest;
import io.metaloom.loom.rest.model.reaction.ReactionListResponse;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.metaloom.loom.rest.model.reaction.ReactionUpdateRequest;

public interface ReactionMethods {

	// Asset
	LoomClientRequest<ReactionResponse> loadAssetReaction(AssetId assetId, UUID uuid);

	default LoomClientRequest<ReactionResponse> loadAssetReaction(UUID assetUuid, UUID uuid) {
		return loadAssetReaction(assetId(assetUuid), uuid);
	}

	LoomClientRequest<ReactionResponse> createAssetReaction(AssetId assetId, ReactionCreateRequest request);

	default LoomClientRequest<ReactionResponse> createAssetReaction(UUID assetUuid, ReactionCreateRequest request) {
		return createAssetReaction(assetId(assetUuid), request);
	}

	LoomClientRequest<ReactionResponse> updateAssetReaction(AssetId assetId, UUID reactionUuid, ReactionUpdateRequest request);

	default LoomClientRequest<ReactionResponse> updateAssetReaction(UUID assetUuid, UUID reactionUuid, ReactionUpdateRequest request) {
		return updateAssetReaction(assetId(assetUuid), reactionUuid, request);
	}

	LoomClientRequest<ReactionListResponse> listAssetReaction(AssetId assetId);

	default LoomClientRequest<ReactionListResponse> listAssetReaction(UUID assetUuid) {
		return listAssetReaction(assetId(assetUuid));
	}

	LoomClientRequest<NoResponse> deleteAssetReaction(AssetId assetId, UUID uuid);

	default LoomClientRequest<NoResponse> deleteAssetReaction(UUID assetUuid, UUID uuid) {
		return deleteAssetReaction(assetId(assetUuid), uuid);
	}

	// Comment
	LoomClientRequest<ReactionResponse> loadCommentReaction(UUID commentUuid, UUID uuid);

	LoomClientRequest<ReactionResponse> createCommentReaction(UUID commentUuid, ReactionCreateRequest request);

	LoomClientRequest<ReactionResponse> updateCommentReaction(UUID commentUuid, UUID uuid, ReactionUpdateRequest request);

	LoomClientRequest<ReactionListResponse> listCommentReaction(UUID commentUuid);

	LoomClientRequest<NoResponse> deleteCommentReaction(UUID commentUuid, UUID uuid);

	// Task
	LoomClientRequest<ReactionResponse> loadTaskReaction(UUID taskUuid, UUID uuid);

	LoomClientRequest<ReactionResponse> createTaskReaction(UUID taskUuid, ReactionCreateRequest request);

	LoomClientRequest<ReactionResponse> updateTaskReaction(UUID taskUuid, UUID uuid, ReactionUpdateRequest request);

	LoomClientRequest<ReactionListResponse> listTaskReaction(UUID taskUuid);

	LoomClientRequest<NoResponse> deleteTaskReaction(UUID taskUuid, UUID uuid);
}
