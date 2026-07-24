package io.metaloom.loom.agent.chat.dagger;

import java.util.Set;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.agent.chat.rest.ChatSessionEndpoint;
import io.metaloom.loom.agent.chat.rest.ChatStreamEndpoint;
import io.metaloom.loom.agent.chat.rest.SessionFsEndpoint;
import io.metaloom.loom.rest.dagger.RESTEndpoints;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;

/**
 * Contributes the chat agent service endpoints to the REST endpoint set.
 */
@Module
public class ChatEndpointModule {

	@ElementsIntoSet
	@Provides
	@RESTEndpoints
	static Set<RESTEndpoint> chatEndpoints(ChatStreamEndpoint chatStreamEndpoint, SessionFsEndpoint sessionFsEndpoint, ChatSessionEndpoint chatSessionEndpoint) {
		return Set.of(chatStreamEndpoint, sessionFsEndpoint, chatSessionEndpoint);
	}
}
