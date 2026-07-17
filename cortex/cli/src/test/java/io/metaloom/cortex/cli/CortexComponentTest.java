package io.metaloom.cortex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.Cortex;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.cli.dagger.CortexComponent;
import io.metaloom.cortex.cli.dagger.DaggerCortexComponent;
import picocli.CommandLine;

public class CortexComponentTest {

	@Test
	public void testDaggerSetup() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Paths.get("target/test-meta"));
		CortexComponent component = DaggerCortexComponent.builder().options(options).build();
		Cortex cortex = component.cortex();
		// cortex.checkNodes();
		CommandLine cli = component.cli();
		cli.execute("-help");
	}
	
	@Test
	public void testSubCompoentHandling() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Paths.get("target/test-meta"));
		CortexComponent component = DaggerCortexComponent.builder().options(options).build();
		LoomMedia media = component.loader().load(Paths.get("target/test"));
		assertNotNull(media);
		assertEquals("target/test", media.path().toString());
	}

}
