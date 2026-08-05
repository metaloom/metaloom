package io.metaloom.loom.rest.model.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * The only mutable property of a notification.
 *
 * <p>
 * Everything else is a record of something that happened and is therefore not editable: a client that could rewrite the title or the subject could
 * fabricate history.
 * </p>
 */
public class NotificationUpdateRequest implements RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Whether the entry has been read. Setting it back to false is allowed - a user may want to revisit something.")
	private Boolean read;

	public Boolean getRead() {
		return read;
	}

	public NotificationUpdateRequest setRead(Boolean read) {
		this.read = read;
		return this;
	}

}
