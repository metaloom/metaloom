package io.metaloom.cortex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.loom.test.LocalTestData;

public class CLITest {

	@BeforeAll
	public static void setup() throws IOException {
		LocalTestData.resetXattr();
	}

	@Test
	public void testCLI() {
		String path = LocalTestData.localDir().toString();
		CortexOptions defaultOptions = createOptions();
		int code = CortexCustomCLIMain.execute(defaultOptions, "po", "run", "-a", "sha256,sha512,custom", path);
		assertEquals(0, code, "The command should not have failed");
	}

	private CortexOptions createOptions() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Path.of("target", "meta"));
		return options;
	}
}
