package io.metaloom.loom.rest.model.info;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Loom instance / system info response model.
 * <p>
 * Sourced from the {@code loom} singleton system row (DB schema revision +
 * last-used timestamp) plus the running server {@code version}.
 */
public class RESTInfoResponse implements RestResponseModel<RESTInfoResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Version of the running Loom server")
	private String version;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Applied database schema revision from the loom system row")
	private String dbRevision;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Timestamp at which the Loom instance was last used (ISO-8601)")
	private String lastUsed;

	public RESTInfoResponse() {
	}

	public String getVersion() {
		return version;
	}

	public RESTInfoResponse setVersion(String version) {
		this.version = version;
		return self();
	}

	public String getDbRevision() {
		return dbRevision;
	}

	public RESTInfoResponse setDbRevision(String dbRevision) {
		this.dbRevision = dbRevision;
		return self();
	}

	public String getLastUsed() {
		return lastUsed;
	}

	public RESTInfoResponse setLastUsed(String lastUsed) {
		this.lastUsed = lastUsed;
		return self();
	}

	@Override
	public RESTInfoResponse self() {
		return this;
	}
}
