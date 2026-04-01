package io.metaloom.loom.rest.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.MetaModel;
import io.vertx.core.json.JsonObject;

/**
 * Abstract class for responses which contain editor and creator information.
 */
public abstract class AbstractCreatorEditorRestResponse<T extends AbstractCreatorEditorRestResponse<T>> extends AbstractResponse<T>
	implements MetaModel<T> {

	private CreatorEditorStatus status = new CreatorEditorStatus();

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	public AbstractCreatorEditorRestResponse() {
	}

	public CreatorEditorStatus getStatus() {
		return status;
	}

	public T setStatus(CreatorEditorStatus status) {
		this.status = status;
		return self();
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public T setMeta(JsonObject meta) {
		this.meta = meta;
		return self();
	}

}
