package io.metaloom.cortex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.cli.cmd.ServerCommand;
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

	@Test
	void testNodeIdFlagPopulatesOptions() {
		CortexCLI cli = new CortexCLI();
		new CommandLine(cli).parseArgs("--node-id", "worker-a");

		assertTrue(cli.hasNodeId(), "A provided --node-id must register as present");
		assertEquals("worker-a", cli.getNodeId());
		assertEquals("worker-a", cli.toCortexOptions().getNodeId());
	}

	@Test
	void testUnsetNodeIdIsAbsent() {
		CortexCLI cli = new CortexCLI();
		new CommandLine(cli).parseArgs();

		assertFalse(cli.hasNodeId(), "An unset --node-id must be reported as absent");
		assertTrue(cli.toCortexOptions().getNodeId() == null, "An unset --node-id must leave options null");
	}

	/**
	 * Starting the server without a worker id must fail up front with a usage error whose message
	 * names the flag and explains the uniqueness/stability requirement - not fall back to a
	 * generated id. The {@code Cortex} is never reached, so a null is safe here.
	 */
	@Test
	void testServerStartRequiresNodeId() {
		CortexCLI parent = new CortexCLI();
		ServerCommand server = new ServerCommand(null);
		CommandLine cmd = new CommandLine(parent);
		cmd.addSubcommand("server", server);

		StringWriter err = new StringWriter();
		cmd.setErr(new PrintWriter(err));
		int exit = cmd.execute("server", "start");

		assertEquals(2, exit, "Missing --node-id must be a usage error (exit code 2)");
		String errText = err.toString();
		assertTrue(errText.contains("--node-id"), "Error must name the --node-id flag: " + errText);
		assertTrue(errText.contains("CORTEX_NODE_ID"), "Error must name the env var: " + errText);
		assertTrue(errText.contains("unique per worker"), "Error must explain the uniqueness requirement: " + errText);
	}

	@Test
	void testServerStartAcceptsNodeId() {
		// With a node id present, requireNodeId() passes; we stop before cortex.run() by asserting
		// the guard alone does not reject. Executing the command would start Cortex, so we validate
		// the guard's precondition via the parsed model instead.
		CortexCLI parent = new CortexCLI();
		new CommandLine(parent).parseArgs("--node-id", "worker-b");
		assertTrue(parent.hasNodeId(), "requireNodeId() must accept a configured id");
	}
}
