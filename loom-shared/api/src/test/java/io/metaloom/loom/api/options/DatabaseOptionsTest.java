package io.metaloom.loom.api.options;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class DatabaseOptionsTest {

	static Map<String, String> testEnv = new HashMap<>();

	@BeforeAll
	public static void setEnvironmentMap() {
		OptionUtils.envLookup = testEnv::get;
		testEnv.put("LOOM_DB_HOST", "test-host");
	}

	@Test
	public void testOptions() {
		DatabaseOptions options = new DatabaseOptions();
		options.overrideWithEnv();
		assertEquals("test-host", options.getHost());
	}

}
