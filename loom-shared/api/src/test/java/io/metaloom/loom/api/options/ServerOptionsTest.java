package io.metaloom.loom.api.options;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ServerOptionsTest {

	private static Function<String, String> originalLookup;
	private static final Map<String, String> testEnv = new HashMap<>();

	@BeforeAll
	public static void setEnvironmentMap() {
		originalLookup = OptionUtils.envLookup;
		OptionUtils.envLookup = testEnv::get;
	}

	@AfterAll
	public static void restoreEnvironmentMap() {
		OptionUtils.envLookup = originalLookup;
	}

	@Test
	public void testMcpPortDefault() {
		ServerOptions options = new ServerOptions();
		assertEquals(ServerOptions.DEFAULT_MCP_PORT, options.getMcpPort());
		assertEquals(4041, options.getMcpPort());
	}

	@Test
	public void testMcpPortEnvOverride() {
		testEnv.clear();
		testEnv.put("LOOM_SERVER_MCP_PORT", "5050");

		ServerOptions options = new ServerOptions();
		options.overrideWithEnv();

		assertEquals(5050, options.getMcpPort());
		// Unrelated ports keep their defaults.
		assertEquals(ServerOptions.DEFAULT_REST_PORT, options.getRestPort());
	}

	@Test
	public void testBindAddressEnvOverride() {
		testEnv.clear();
		testEnv.put("LOOM_SERVER_GRPC_BIND_ADDRESS", "127.0.0.1");

		ServerOptions options = new ServerOptions();
		options.overrideWithEnv();

		assertEquals("127.0.0.1", options.getBindAddress());
	}

	@Test
	public void testConfiguredMcpPortIsUsed() {
		// Simulates a value read from the config file (server.mcpPort).
		ServerOptions options = new ServerOptions().setMcpPort(9099);
		assertEquals(9099, options.getMcpPort());
	}
}
