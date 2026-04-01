package io.metaloom.loom.core.endpoint;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.test.ModelTest;
import io.metaloom.loom.test.data.TestValues;

public interface EndpointTest extends TestValues, ModelTest {

	LoomHttpClient httpClient();

}
