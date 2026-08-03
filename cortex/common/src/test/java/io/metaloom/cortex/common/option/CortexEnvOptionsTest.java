package io.metaloom.cortex.common.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.S3EventOptions;

/**
 * Cortex has no command line, so the environment is the only runtime override a container gets. What used to be verified against picocli flags is
 * verified here against {@link CortexEnvOptions}.
 */
public class CortexEnvOptionsTest {

	private final Map<String, String> env = new HashMap<>();

	@BeforeEach
	public void useTestEnv() {
		CortexEnvOptions.envLookup = env::get;
	}

	@AfterEach
	public void restoreEnv() {
		CortexEnvOptions.envLookup = System::getenv;
	}

	@Test
	public void testWorkerIdentityAndLoomEndpoint() {
		env.put("LOOM_HOST", "loom.example.com");
		env.put("LOOM_PORT", "8080");
		env.put("CORTEX_NODE_ID", "worker-a");
		env.put("CORTEX_MONITORING_PORT", "9099");
		env.put("CORTEX_META_PATH", "/meta");
		env.put("CORTEX_DRAIN_TIMEOUT_MS", "120000");

		CortexOptions options = CortexEnvOptions.applyEnv(new CortexOptions());

		assertEquals("loom.example.com", options.getLoom().getHostname());
		assertEquals(8080, options.getLoom().getPort());
		assertEquals("worker-a", options.getNodeId());
		assertEquals(9099, options.getMonitoringPort());
		assertEquals(Paths.get("/meta"), options.getMetaPath());
		assertEquals(120000L, options.getDrainTimeoutMs());
	}

	@Test
	public void testNodeWhitelistAndBlacklistPopulateOptions() {
		env.put("CORTEX_NODE_WHITELIST", "sha256,sha512");
		env.put("CORTEX_NODE_BLACKLIST", "whisper");

		CortexOptions options = CortexEnvOptions.applyEnv(new CortexOptions());

		assertEquals(Set.of("sha256", "sha512"), options.getNodeWhitelist());
		assertEquals(Set.of("whisper"), options.getNodeBlacklist());
	}

	@Test
	public void testUnsetRestrictionsLeaveOptionsUnrestricted() {
		CortexOptions options = CortexEnvOptions.applyEnv(new CortexOptions());

		// null (not an empty set) is the "unrestricted" signal the worker announces.
		assertNull(options.getNodeWhitelist(), "An unset whitelist must stay null (unrestricted)");
		assertNull(options.getNodeBlacklist(), "An unset blacklist must stay null (refuse nothing)");
		assertNull(options.getNodeId(), "An unset CORTEX_NODE_ID must leave the options null");
	}

	/**
	 * A variable that is present but empty is what an unset value looks like in most container runtimes, so it must not overwrite a configured value
	 * with a blank one.
	 */
	@Test
	public void testBlankValuesAreIgnored() {
		env.put("CORTEX_NODE_ID", "  ");
		CortexOptions options = new CortexOptions();
		options.setNodeId("worker-from-yaml");

		CortexEnvOptions.applyEnv(options);

		assertEquals("worker-from-yaml", options.getNodeId());
	}

	@Test
	public void testCloudSettingsAreApplied() {
		env.put("CORTEX_S3_ENDPOINT", "http://minio:9000");
		env.put("CORTEX_S3_PATH_STYLE", "true");
		env.put("CORTEX_S3_MAX_OBJECT_SIZE", "1048576");
		env.put("CORTEX_S3_EVENTS_ENABLED", "true");
		env.put("CORTEX_S3_EVENTS_MODE", "sqs");
		env.put("CORTEX_GDRIVE_MAX_RETRIES", "7");
		env.put("CORTEX_ONEDRIVE_TENANT_ID", "tenant-1");

		CortexOptions options = CortexEnvOptions.applyEnv(new CortexOptions());

		assertEquals("http://minio:9000", options.getS3().getEndpoint());
		assertEquals(Boolean.TRUE, options.getS3().getPathStyleAccess());
		assertEquals(1048576L, options.getS3().getMaxObjectSize());
		assertTrue(options.getS3().getEvents().isEnabled());
		assertEquals(S3EventOptions.Mode.SQS, options.getS3().getEvents().getMode());
		assertEquals(7, options.getGdrive().getMaxRetries());
		assertEquals("tenant-1", options.getOnedrive().getTenantId());
	}

	/**
	 * A typo must fail the startup rather than read as "off": a mistyped {@code CORTEX_S3_EVENTS_ENABLED} would otherwise silently fall back to a full
	 * bucket listing on every run.
	 */
	@Test
	public void testInvalidValuesFailFast() {
		env.put("CORTEX_S3_EVENTS_ENABLED", "yes");
		assertThrows(IllegalArgumentException.class, () -> CortexEnvOptions.applyEnv(new CortexOptions()));

		env.clear();
		env.put("LOOM_PORT", "not-a-port");
		assertThrows(IllegalArgumentException.class, () -> CortexEnvOptions.applyEnv(new CortexOptions()));

		env.clear();
		env.put("CORTEX_S3_EVENTS_MODE", "carrier-pigeon");
		assertThrows(IllegalArgumentException.class, () -> CortexEnvOptions.applyEnv(new CortexOptions()));
	}
}
