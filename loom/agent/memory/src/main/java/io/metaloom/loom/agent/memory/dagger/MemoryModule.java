package io.metaloom.loom.agent.memory.dagger;

import java.util.Set;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.agent.memory.rest.MemoryDenyRuleEndpoint;
import io.metaloom.loom.agent.memory.rest.MemoryEndpoint;
import io.metaloom.loom.agent.memory.sandbox.MemoryMaterializer;
import io.metaloom.loom.agent.sandbox.SandboxProvisionListener;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.rest.dagger.RESTEndpoints;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;

/**
 * Contributes the memory materializer to the sandbox provisioning hooks.
 *
 * <p>Kept separate from {@code MemoryToolModule} because the two switches differ: the tools follow {@code LOOM_AGENT_MEMORY_ENABLED} while materializing
 * the read-only folder additionally requires {@code LOOM_AGENT_MEMORY_MOUNT_ENABLED} — memory is fully usable through the tools alone.</p>
 */
@Module
public class MemoryModule {

	@ElementsIntoSet
	@Provides
	static Set<SandboxProvisionListener> memoryProvisionListeners(LoomOptions options, MemoryMaterializer materializer) {
		if (!options.getMemory().isEnabled() || !options.getMemory().isMountEnabled()) {
			return Set.of();
		}
		return Set.of(materializer);
	}

	/**
	 * The REST surface follows the feature switch only — browsing and editing notes works regardless of whether they are materialized into a container.
	 */
	@ElementsIntoSet
	@Provides
	@RESTEndpoints
	static Set<RESTEndpoint> memoryEndpoints(LoomOptions options, MemoryEndpoint memoryEndpoint, MemoryDenyRuleEndpoint memoryDenyRuleEndpoint) {
		if (!options.getMemory().isEnabled()) {
			return Set.of();
		}
		return Set.of(memoryEndpoint, memoryDenyRuleEndpoint);
	}

}
