package io.metaloom.loom.rest.model.tag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class TagResponse extends AbstractCreatorEditorRestResponse<TagResponse> implements TagModel<TagResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Text value of the tag.")
	private String name;

	@JsonPropertyDescription("The area which has been tagged")
	private AreaInfo area;

	@JsonPropertyDescription("The color hex code for the tag")
	private String color;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Name of the collection to which the tag belongs.")
	private String collection;

	public TagResponse() {
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public TagResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getCollection() {
		return collection;
	}

	@Override
	public TagResponse setCollection(String collection) {
		this.collection = collection;
		return this;
	}

	@Override
	public AreaInfo getArea() {
		return area;
	}

	@Override
	public TagResponse setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	@Override
	public String getColor() {
		return color;
	}

	@Override
	public TagResponse setColor(String color) {
		this.color = color;
		return this;
	}

	@Override
	public TagResponse self() {
		return this;
	}
}
