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
	@JsonPropertyDescription("Spatial or temporal area of the asset that the tag references. Two calls naming different areas place the tag twice on the same asset - once per face, once per timecode.")
	private AreaInfo area;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Which node kind is attaching the tag. Left unset by a person, and recorded as 'manual'.")
	private String nodeKind;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Pipeline node id of the writer, so two instances of one node kind stay distinguishable.")
	private String nodeId;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Version of the answer the writer stands behind; it changes when the meaning of the tag changes.")
	private String producerVersion;

	@JsonProperty(required = false)
	@JsonPropertyDescription("How sure the writer is, 0.0 - 1.0.")
	private Float confidence;

	public TagCreateRequest() {
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public TagCreateRequest setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public String getNodeId() {
		return nodeId;
	}

	public TagCreateRequest setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public TagCreateRequest setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	public Float getConfidence() {
		return confidence;
	}

	public TagCreateRequest setConfidence(Float confidence) {
		this.confidence = confidence;
		return this;
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
