package io.metaloom.loom.rest.model.webhook;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class WebhookUpdateRequest implements RestRequestModel, MetaModel<WebhookUpdateRequest> {

	@JsonProperty(required = false)
	@JsonPropertyDescription("The url which should be invoked by the webhook.")
	private String url;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The triggers define the events on which the hook should be invoked.")
	private List<WebhookTrigger> triggers;

	@JsonPropertyDescription("The specified token which will be included in every webhook request to the endpoint. The endpoint can use this information to verify that the request is legitimate.")
	private String secretToken;

	@JsonPropertyDescription("Flag to enable or disable the webhook.")
	private Boolean active;

	private JsonObject meta;

	public WebhookUpdateRequest() {
	}

	public String getUrl() {
		return url;
	}

	public WebhookUpdateRequest setUrl(String url) {
		this.url = url;
		return this;
	}

	public List<WebhookTrigger> getTriggers() {
		return triggers;
	}

	public WebhookUpdateRequest setTriggers(List<WebhookTrigger> triggers) {
		this.triggers = triggers;
		return this;
	}

	public String getSecretToken() {
		return secretToken;
	}

	public WebhookUpdateRequest setSecretToken(String secretToken) {
		this.secretToken = secretToken;
		return this;
	}

	public Boolean getActive() {
		return active;
	}

	public WebhookUpdateRequest setActive(Boolean active) {
		this.active = active;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public WebhookUpdateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public WebhookUpdateRequest self() {
		return this;
	}

}
