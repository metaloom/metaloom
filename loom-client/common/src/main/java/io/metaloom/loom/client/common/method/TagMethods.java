package io.metaloom.loom.client.common.method;

import static io.metaloom.loom.api.asset.AssetId.assetId;

import java.util.UUID;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagListResponse;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.model.tag.TagUpdateRequest;
import io.metaloom.utils.hash.SHA512;

public interface TagMethods {

	LoomClientRequest<TagResponse> loadTag(UUID tagUuid);

	LoomClientRequest<TagResponse> createTag(TagCreateRequest request);

	LoomClientRequest<TagResponse> updateTag(UUID tagUuid, TagUpdateRequest request);

	LoomClientRequest<TagListResponse> listTags();

	LoomClientRequest<NoResponse> deleteTag(UUID tagUuid);

	// TAG - ASSET

	LoomClientRequest<TagResponse> tagAsset(AssetId assetId, TagCreateRequest request);

	default LoomClientRequest<TagResponse> tagAsset(SHA512 assetHash, TagCreateRequest request) {
		return tagAsset(assetId(assetHash), request);
	}

	default LoomClientRequest<TagResponse> tagAsset(UUID assetUuid, TagCreateRequest request) {
		return tagAsset(assetId(assetUuid), request);
	}

	// UNTAG - ASSET

	LoomClientRequest<NoResponse> untagAsset(AssetId assetId, UUID tagUuid);

	default LoomClientRequest<NoResponse> untagAsset(SHA512 assetHash, UUID tagUuid) {
		return untagAsset(assetId(assetHash), tagUuid);
	}

	default LoomClientRequest<NoResponse> untagAsset(UUID assetUuid, UUID tagUuid) {
		return untagAsset(assetId(assetUuid), tagUuid);
	}

}
