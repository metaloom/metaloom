package io.metaloom.loom.rest.model.tag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.vertx.core.json.JsonObject;

public class TagCreateRequest implements MetaModel<TagCreateRequest>, RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Text value of the tag.")
	private String name;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Name of the collection to which the tag belongs.")
	private String collection;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Spatial or temporal area of the asset that the tag references.")
	private AreaInfo area;

	public TagCreateRequest() {
	}

	public String getName() {
		return name;
	}

	public TagCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	public String getCollection() {
		return collection;
	}

	public TagCreateRequest setCollection(String collection) {
		this.collection = collection;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public TagCreateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	public AreaInfo getArea() {
		return area;
	}

	public TagCreateRequest setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	@Override
	public TagCreateRequest self() {
		return this;
	}

}
