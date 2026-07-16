package io.metaloom.loom.rest.model.health;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Health check response model.
 */
public class HealthCheckResponse implements RestResponseModel<HealthCheckResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Overall health status of the service")
	private String status;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Version of the Loom service")
	private String version;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Database connection status")
	private String database;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Timestamp of the health check")
	private String timestamp;

	public HealthCheckResponse() {
	}

	public String getStatus() {
		return status;
	}

	public HealthCheckResponse setStatus(String status) {
		this.status = status;
		return self();
	}

	public String getVersion() {
		return version;
	}

	public HealthCheckResponse setVersion(String version) {
		this.version = version;
		return self();
	}

	public String getDatabase() {
		return database;
	}

	public HealthCheckResponse setDatabase(String database) {
		this.database = database;
		return self();
	}

	public String getTimestamp() {
		return timestamp;
	}

	public HealthCheckResponse setTimestamp(String timestamp) {
		this.timestamp = timestamp;
		return self();
	}

	@Override
	public HealthCheckResponse self() {
		return this;
	}
}