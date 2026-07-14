package io.metaloom.loom.core.endpoint;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.graphql.GraphQLRequest;
import io.metaloom.loom.rest.model.graphql.GraphQLResponse;

public abstract class AbstractGraphQLEndpointTest extends AbstractEndpointTest implements GraphQLEndpointTestcases {

	@Test
	@Override
	public void testExecuteGraphQL() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			testExecuteGraphQL(client);
		}
	}

	protected abstract void testExecuteGraphQL(LoomHttpClient client) throws Exception;

}