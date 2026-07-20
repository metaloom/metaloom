package io.metaloom.cortex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.CortexOptions;
import picocli.CommandLine;

/**
 * The startup node-restriction flags must land in {@link CortexOptions}, which is what the
 * worker announces to Loom at registration. A comma-separated {@code --node-whitelist} /
 * {@code --node-blacklist} becomes a set; when unset the restriction stays null so
 * {@code CortexOptions} reads it as "unrestricted" rather than "run nothing".
 */
public class CortexCLITest {

	@Test
	void testNodeWhitelistAndBlacklistFlagsPopulateOptions() {
		CortexCLI cli = new CortexCLI();
		new CommandLine(cli).parseArgs("--node-whitelist", "sha256,sha512", "--node-blacklist", "whisper");

		CortexOptions options = cli.toCortexOptions();
		assertEquals(Set.of("sha256", "sha512"), options.getNodeWhitelist());
		assertEquals(Set.of("whisper"), options.getNodeBlacklist());
	}

	@Test
	void testUnsetRestrictionsLeaveOptionsUnrestricted() {
		CortexCLI cli = new CortexCLI();
		new CommandLine(cli).parseArgs();

		CortexOptions options = cli.toCortexOptions();
		// null (not an empty set) is the "unrestricted" signal the worker announces.
		assertTrue(options.getNodeWhitelist() == null, "An unset whitelist must stay null (unrestricted)");
		assertTrue(options.getNodeBlacklist() == null, "An unset blacklist must stay null (refuse nothing)");
	}
}
