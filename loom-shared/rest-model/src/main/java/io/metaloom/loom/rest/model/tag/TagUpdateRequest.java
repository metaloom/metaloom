package io.metaloom.loom.rest.model.tag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class TagUpdateRequest implements MetaModel<TagUpdateRequest>, RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Text value of the tag.")
	private String name;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Name of the collection to which the tag belongs.")
	private String collection;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	public TagUpdateRequest() {
	}

	public String getName() {
		return name;
	}

	public TagUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	public String getCollection() {
		return collection;
	}

	public TagUpdateRequest setCollection(String collection) {
		this.collection = collection;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public TagUpdateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public TagUpdateRequest self() {
		return this;
	}

}
