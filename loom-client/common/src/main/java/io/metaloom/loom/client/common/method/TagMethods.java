package io.metaloom.loom.client.common.method;

import static io.metaloom.loom.api.asset.AssetId.assetId;

import java.util.UUID;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.tag.AssetTagBulkRequest;
import io.metaloom.loom.rest.model.tag.AssetTagBulkResponse;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagListResponse;
import io.metaloom.loom.rest.model.tag.TagRatingRequest;
import io.metaloom.loom.rest.model.tag.TagRatingResponse;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.model.tag.TagUpdateRequest;
import io.metaloom.utils.hash.SHA512;

public interface TagMethods {

	LoomClientRequest<TagResponse> loadTag(UUID tagUuid);

	LoomClientRequest<TagResponse> createTag(TagCreateRequest request);

	LoomClientRequest<TagResponse> updateTag(UUID tagUuid, TagUpdateRequest request);

	LoomClientRequest<TagListResponse> listTags();

	LoomClientRequest<NoResponse> deleteTag(UUID tagUuid);

	// TAG - USER RATING

	LoomClientRequest<TagRatingResponse> rateTag(UUID tagUuid, TagRatingRequest request);

	LoomClientRequest<TagRatingResponse> loadTagRating(UUID tagUuid);

	LoomClientRequest<NoResponse> deleteTagRating(UUID tagUuid);

	// TAG - ASSET

	LoomClientRequest<TagResponse> tagAsset(AssetId assetId, TagCreateRequest request);

	default LoomClientRequest<TagResponse> tagAsset(SHA512 assetHash, TagCreateRequest request) {
		return tagAsset(assetId(assetHash), request);
	}

	default LoomClientRequest<TagResponse> tagAsset(UUID assetUuid, TagCreateRequest request) {
		return tagAsset(assetId(assetUuid), request);
	}

	// TAG - ASSET (BULK)

	/**
	 * Attach a whole set of tags to one asset, and detach the tags the request names, in a single call.
	 *
	 * <p>
	 * Use this rather than a loop over {@link #tagAsset(AssetId, TagCreateRequest)} whenever more than one tag is involved: the server applies the set
	 * in one transaction, and a pipeline tagging a library issues one request per asset instead of one per tag.
	 * </p>
	 *
	 * <p>
	 * Requires <code>TAG_ASSET</code>, and <code>UNTAG_ASSET</code> as well when the request withdraws anything.
	 * </p>
	 */
	LoomClientRequest<AssetTagBulkResponse> bulkTagAsset(AssetId assetId, AssetTagBulkRequest request);

	default LoomClientRequest<AssetTagBulkResponse> bulkTagAsset(SHA512 assetHash, AssetTagBulkRequest request) {
		return bulkTagAsset(assetId(assetHash), request);
	}

	default LoomClientRequest<AssetTagBulkResponse> bulkTagAsset(UUID assetUuid, AssetTagBulkRequest request) {
		return bulkTagAsset(assetId(assetUuid), request);
	}

	// UNTAG - ASSET

	/**
	 * Remove the tag from the asset, with <strong>every</strong> placement of it.
	 *
	 * <p>
	 * A tag may sit on one asset several times - once per face, once per timecode. This clears the picture; {@link #removeTagPlacement(AssetId, UUID)}
	 * clears one face.
	 * </p>
	 */
	LoomClientRequest<NoResponse> untagAsset(AssetId assetId, UUID tagUuid);

	default LoomClientRequest<NoResponse> untagAsset(SHA512 assetHash, UUID tagUuid) {
		return untagAsset(assetId(assetHash), tagUuid);
	}

	default LoomClientRequest<NoResponse> untagAsset(UUID assetUuid, UUID tagUuid) {
		return untagAsset(assetId(assetUuid), tagUuid);
	}

	/**
	 * Remove one placement of a tag from an asset, keeping its other placements.
	 *
	 * <p>
	 * The placement uuid comes from {@code TagReference.placementUuid} on the asset, or from the response of the call that created it.
	 * </p>
	 */
	LoomClientRequest<NoResponse> removeTagPlacement(AssetId assetId, UUID placementUuid);

	default LoomClientRequest<NoResponse> removeTagPlacement(SHA512 assetHash, UUID placementUuid) {
		return removeTagPlacement(assetId(assetHash), placementUuid);
	}

	default LoomClientRequest<NoResponse> removeTagPlacement(UUID assetUuid, UUID placementUuid) {
		return removeTagPlacement(assetId(assetUuid), placementUuid);
	}

}
