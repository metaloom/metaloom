package io.metaloom.loom.rest.model.tag;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * What a bulk tagging call did to one asset.
 *
 * <p>
 * The {@link #getTags() tags} carry the persisted rows, not the request's: a tag which already existed comes back with the uuid, colour and meta it
 * carries in the catalog. That uuid is what a writer stores in order to withdraw the tag on a later run.
 * </p>
 */
public class AssetTagBulkResponse implements RestResponseModel<AssetTagBulkResponse> {

	@JsonPropertyDescription("The tags now attached to the asset by this call, as they are persisted.")
	private List<TagResponse> tags = new ArrayList<>();

	@JsonPropertyDescription("How many tags the request asked to attach.")
	private int total;

	@JsonPropertyDescription("How many tags were attached.")
	private int applied;

	@JsonPropertyDescription("How many tag attachments were removed from the asset.")
	private int withdrawn;

	public List<TagResponse> getTags() {
		return tags;
	}

	public AssetTagBulkResponse setTags(List<TagResponse> tags) {
		this.tags = tags;
		return this;
	}

	public AssetTagBulkResponse add(TagResponse tag) {
		this.tags.add(tag);
		return this;
	}

	public int getTotal() {
		return total;
	}

	public AssetTagBulkResponse setTotal(int total) {
		this.total = total;
		return this;
	}

	public int getApplied() {
		return applied;
	}

	public AssetTagBulkResponse setApplied(int applied) {
		this.applied = applied;
		return this;
	}

	public int getWithdrawn() {
		return withdrawn;
	}

	public AssetTagBulkResponse setWithdrawn(int withdrawn) {
		this.withdrawn = withdrawn;
		return this;
	}

	@Override
	public AssetTagBulkResponse self() {
		return this;
	}

}
