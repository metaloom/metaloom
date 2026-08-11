package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.share.ShareAnnotationListResponse;
import io.metaloom.loom.rest.model.share.ShareAnnotationRequest;
import io.metaloom.loom.rest.model.share.ShareAnnotationResponse;
import io.metaloom.loom.rest.model.share.ShareChallengeResponse;
import io.metaloom.loom.rest.model.share.ShareCommentListResponse;
import io.metaloom.loom.rest.model.share.ShareCommentRequest;
import io.metaloom.loom.rest.model.share.ShareCommentResponse;
import io.metaloom.loom.rest.model.share.ShareCreateRequest;
import io.metaloom.loom.rest.model.share.ShareFeedbackResponse;
import io.metaloom.loom.rest.model.share.ShareListResponse;
import io.metaloom.loom.rest.model.share.ShareReactionListResponse;
import io.metaloom.loom.rest.model.share.ShareReactionRequest;
import io.metaloom.loom.rest.model.share.ShareReactionResponse;
import io.metaloom.loom.rest.model.share.ShareResponse;
import io.metaloom.loom.rest.model.share.ShareSessionRequest;
import io.metaloom.loom.rest.model.share.ShareSessionResponse;
import io.metaloom.loom.rest.model.share.ShareUpdateRequest;
import io.metaloom.loom.rest.model.share.SharedAssetListResponse;
import io.metaloom.loom.rest.model.share.SharedAssetResponse;

/**
 * Share links and the customer-facing area behind them.
 *
 * <p>
 * Two halves with two different credentials. The {@code *Share*} methods at the top address {@code /share-links} and need a logged-in user holding
 * the {@code *_SHARE} permissions. The {@code *Shared*} methods below address {@code /shares/{slug}} as a customer would: they need no account, and
 * everything after {@link #openShare(String, ShareSessionRequest)} needs the session token that call returns, set on the client with
 * {@code setShareSessionToken}.
 * </p>
 */
public interface ShareMethods {

	// --- Owner side: /share-links ---

	LoomClientRequest<ShareResponse> createShare(ShareCreateRequest request);

	LoomClientRequest<ShareResponse> loadShare(UUID shareUuid);

	LoomClientRequest<ShareResponse> updateShare(UUID shareUuid, ShareUpdateRequest request);

	LoomClientRequest<ShareListResponse> listShares();

	LoomClientRequest<NoResponse> deleteShare(UUID shareUuid);

	/**
	 * The share links pointing at one asset.
	 */
	LoomClientRequest<ShareListResponse> listAssetShares(UUID assetUuid);

	/**
	 * The share links pointing at one collection.
	 */
	LoomClientRequest<ShareListResponse> listCollectionShares(UUID collectionUuid);

	/**
	 * Everything the visitor said through one link.
	 */
	LoomClientRequest<ShareFeedbackResponse> loadShareFeedback(UUID shareUuid);

	// --- Customer side: /shares/{slug} ---

	/**
	 * What a link asks for before it will open. Answers 404 for an unknown, revoked or lapsed slug - all three are indistinguishable on purpose.
	 */
	LoomClientRequest<ShareChallengeResponse> loadShareChallenge(String slug);

	/**
	 * Open a link. The returned {@code sessionToken} must be handed to {@code setShareSessionToken} before any of the calls below will work.
	 */
	LoomClientRequest<ShareSessionResponse> openShare(String slug, ShareSessionRequest request);

	LoomClientRequest<SharedAssetListResponse> listSharedAssets(String slug);

	LoomClientRequest<SharedAssetResponse> loadSharedAsset(String slug, UUID assetUuid);

	LoomClientRequest<ShareCommentListResponse> listSharedComments(String slug);

	LoomClientRequest<ShareCommentResponse> createSharedComment(String slug, ShareCommentRequest request);

	LoomClientRequest<ShareCommentResponse> updateSharedComment(String slug, UUID commentUuid, ShareCommentRequest request);

	LoomClientRequest<NoResponse> deleteSharedComment(String slug, UUID commentUuid);

	LoomClientRequest<ShareAnnotationListResponse> listSharedAnnotations(String slug);

	LoomClientRequest<ShareAnnotationResponse> createSharedAnnotation(String slug, ShareAnnotationRequest request);

	LoomClientRequest<ShareAnnotationResponse> updateSharedAnnotation(String slug, UUID annotationUuid, ShareAnnotationRequest request);

	LoomClientRequest<NoResponse> deleteSharedAnnotation(String slug, UUID annotationUuid);

	LoomClientRequest<ShareReactionListResponse> listSharedReactions(String slug);

	LoomClientRequest<ShareReactionResponse> createSharedReaction(String slug, ShareReactionRequest request);

	LoomClientRequest<NoResponse> deleteSharedReaction(String slug, UUID reactionUuid);
}
