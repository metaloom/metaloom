package io.metaloom.loom.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.test.data.TestDataCollection;

public class TestEnvHelperTest {

	@Test
	public void testHelper() throws IOException {
		File expectedPath = new File("target", "test-env-postfix");
		if (expectedPath.exists()) {
			FileUtils.deleteDirectory(expectedPath);
		}
		TestDataCollection data = TestEnvHelper.prepareTestdata("postfix");
		assertTrue(Files.exists(data.root()));
		assertEquals(expectedPath.getAbsolutePath(), data.root().toAbsolutePath());
	}

}
