package io.metaloom.loom.client.http;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.error.LoomHttpClientException;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.loom.rest.model.common.AbstractResponse;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;

public abstract class AbstractHTTPClientTest extends AbstractContainerTest {

	protected LoomHttpClient client;

	@BeforeEach
	public void prepareClient() {
		client = LoomHttpClient.builder()
			.setScheme("http")
			.setHostname(loom.getHost())
			.setPort(loom.httpPort())
			.build();
	}

	@AfterEach
	public void closeClient() {
		if (client != null) {
			client.close();
		}
	}

	/**
	 * Return JSON with name property and specified value.
	 * 
	 * @param name
	 * @return
	 */
	protected String json(String key, String name) {
		return "{ \"" + key + "\": \"" + name + "\"}";
	}

	protected void assertSuccess(RestResponseModel<?> response) {
		// assertTrue("The response should be successful.", response.getResult());
		fail("Not impl");
	}

	protected void assertSuccess(AbstractResponse<?> response) {
		// assertEquals("ok", response.getStatus(), "The response should be successful.");
		fail("Not impl");
	}

	protected <T extends RestResponseModel<T>> T invoke(LoomClientHttpRequest<T> request) throws LoomClientException {
		try {
			T response = request.sync();
			assertSuccess((AbstractResponse) response);
			return response;
		} catch (LoomClientException e) {
			if (e instanceof LoomHttpClientException le) {
				GenericMessageResponse error = le.getResponse();
				fail("Request failed with error " + error.getMessage());
			} else {
				fail("Request failed with error " + e.getMessage());
			}
			return null;
		}
	}

}
