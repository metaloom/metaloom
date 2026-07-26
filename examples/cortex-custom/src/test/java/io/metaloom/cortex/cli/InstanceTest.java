package io.metaloom.cortex.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.Cortex;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.cli.dagger.CortexComponent;

/**
 * Verifies that the custom Cortex daemon can be assembled and that the custom {@code hello-world} node is wired into the instance — without starting the
 * blocking daemon or needing a running Loom backend.
 */
public class InstanceTest {

	@Test
	public void testInstanceAssembly() {
		CortexComponent component = CortexCustomMain.buildComponent(createOptions());

		Cortex cortex = component.cortex();
		assertNotNull(cortex, "The Cortex instance should be assembled");

		// The registry is populated at bootstrap; drive that step directly here (a
		// running daemon does it in CortexBootstrapInitializer before registering).
		component.nodeRegistrar().registerAll();

		// The custom node must be registered with the pipeline node factory. This validates the extension wiring
		// (NodeCollectionModule + PipelineNodeFactoryModule) without eagerly loading heavy nodes (e.g. facedetect models).
		assertTrue(component.nodeFactory().registeredTypes().contains("hello-world"),
			"The custom hello-world node should be registered with the instance");
	}

	private CortexOptions createOptions() {
		CortexOptions options = new CortexOptions();
		// No Loom host configured -> the client is null (offline), so the test needs no running backend.
		options.setMetaPath(Path.of("target", "meta"));
		return options;
	}
}
