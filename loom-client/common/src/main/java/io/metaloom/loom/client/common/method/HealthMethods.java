package io.metaloom.loom.client.common.method;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.health.HealthCheckResponse;

public interface HealthMethods {

	LoomClientRequest<HealthCheckResponse> health();
}