package io.metaloom.loom.agent.sandbox.dagger;

import java.util.Set;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.agent.sandbox.SandboxProvisionListener;

/**
 * Declares the (possibly empty) set of {@link SandboxProvisionListener}s.
 *
 * <p>Without this the orchestrator could not be constructed when no feature contributes a listener. Features add their own {@code @ElementsIntoSet}
 * provider — see the memory module — which keeps the sandbox unaware of them.</p>
 */
@Module
public class SandboxModule {

	@ElementsIntoSet
	@Provides
	static Set<SandboxProvisionListener> defaultProvisionListeners() {
		return Set.of();
	}

}
