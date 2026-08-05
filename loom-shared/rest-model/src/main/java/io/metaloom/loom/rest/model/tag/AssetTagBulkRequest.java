package io.metaloom.loom.rest.model.tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * The set of tags one writer wants on one asset, applied in a single request.
 *
 * <p>
 * Tagging an asset one <code>POST</code> at a time is correct but does not scale: a pipeline node that attaches five tags to a hundred thousand assets
 * issues half a million requests, each one its own transaction and each one firing the trigger that rebuilds the same search document. This request
 * carries the whole set, and the server applies it in one transaction.
 * </p>
 *
 * <p>
 * Entries are <strong>upserted</strong>: a tag is global (<code>UNIQUE (name, collection)</code>), so an entry naming a tag that already exists
 * attaches that tag rather than creating a second one, and re-sending the same set is a no-op.
 * </p>
 *
 * <p>
 * <strong>Withdrawal is explicit, by uuid.</strong> The obvious alternative - "these are the only tags I want, delete the rest" - cannot be done
 * safely today: <code>tag_asset</code> carries no provenance, so the server cannot tell a tag this writer applied from one a person typed, and a
 * desired-set semantic would let a worker delete human curation. Until the join row records who wrote it, the caller names what it wants removed, and
 * only a caller holding <code>UNTAG_ASSET</code> may name anything at all.
 * </p>
 */
public class AssetTagBulkRequest implements RestRequestModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("Collection to use for entries which do not name one. Tags are global, so a collection separates one writer's tags from another's.")
	private String collection;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The tags to attach. Existing tags are reused, and re-sending the same set changes nothing.")
	private List<TagCreateRequest> tags = new ArrayList<>();

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuids of tags to detach from this asset. Requires the UNTAG_ASSET permission. The tags themselves are not deleted, and every placement of a named tag on this asset goes.")
	private List<UUID> withdraw = new ArrayList<>();

	@JsonProperty(required = false)
	@JsonPropertyDescription("Which node kind is attaching these tags, for entries which do not name one. Left unset by a person, and recorded as 'manual'.")
	private String nodeKind;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Pipeline node id of the writer, for entries which do not name one.")
	private String nodeId;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Version of the answer the writer stands behind, for entries which do not name one.")
	private String producerVersion;

	public AssetTagBulkRequest() {
	}

	public String getCollection() {
		return collection;
	}

	public AssetTagBulkRequest setCollection(String collection) {
		this.collection = collection;
		return this;
	}

	public List<TagCreateRequest> getTags() {
		return tags;
	}

	public AssetTagBulkRequest setTags(List<TagCreateRequest> tags) {
		this.tags = tags;
		return this;
	}

	public AssetTagBulkRequest add(TagCreateRequest tag) {
		this.tags.add(tag);
		return this;
	}

	public List<UUID> getWithdraw() {
		return withdraw;
	}

	public AssetTagBulkRequest setWithdraw(List<UUID> withdraw) {
		this.withdraw = withdraw;
		return this;
	}

	public AssetTagBulkRequest withdraw(UUID tagUuid) {
		this.withdraw.add(tagUuid);
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public AssetTagBulkRequest setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public AssetTagBulkRequest setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public AssetTagBulkRequest setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

}
