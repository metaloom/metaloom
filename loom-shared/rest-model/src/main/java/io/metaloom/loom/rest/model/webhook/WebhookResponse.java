package io.metaloom.loom.rest.model.webhook;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class WebhookResponse extends AbstractCreatorEditorRestResponse<WebhookResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The url which should be invoked by the webhook.")
	private String url;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The triggers define the events on which the hook should be invoked.")
	private List<WebhookTrigger> triggers;

	@JsonPropertyDescription("The specified token which will be included in every webhook request to the endpoint. The endpoint can use this information to verify that the request is legitimate.")
	private String secretToken;

	@JsonPropertyDescription("Flag to enable or disable the webhook.")
	private Boolean active;

	public WebhookResponse() {
	}

	public String getUrl() {
		return url;
	}

	public WebhookResponse setUrl(String url) {
		this.url = url;
		return this;
	}

	public List<WebhookTrigger> getTriggers() {
		return triggers;
	}

	public WebhookResponse setTriggers(List<WebhookTrigger> triggers) {
		this.triggers = triggers;
		return this;
	}

	public String getSecretToken() {
		return secretToken;
	}

	public WebhookResponse setSecretToken(String secretToken) {
		this.secretToken = secretToken;
		return this;
	}

	public Boolean getActive() {
		return active;
	}

	public WebhookResponse setActive(Boolean active) {
		this.active = active;
		return this;
	}

	@Override
	public WebhookResponse self() {
		return this;
	}
}
