package io.metaloom.loom.ai.dagger;

import java.util.Set;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.ai.rest.ChatStreamEndpoint;
import io.metaloom.loom.rest.dagger.RESTEndpoints;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;

/**
 * Contributes the ai service endpoints to the REST endpoint set.
 */
@Module
public class AiEndpointModule {

	@ElementsIntoSet
	@Provides
	@RESTEndpoints
	static Set<RESTEndpoint> aiEndpoints(ChatStreamEndpoint chatStreamEndpoint) {
		return Set.of(chatStreamEndpoint);
	}
}
