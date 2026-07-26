package io.metaloom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.cli.dagger.DaggerCliComponent;
import picocli.CommandLine;

/**
 * Guards the command tree itself.
 *
 * <p>The failure this exists for: {@code DaggerCliFactory} falls back to picocli's reflective
 * factory for any class it has no binding for. That produces a command object with null
 * dependencies which only fails when someone runs it - so a forgotten entry in
 * {@code CommandModule} would otherwise ship silently.</p>
 */
public class CliCommandTreeTest {

	private CommandLine cli() {
		return DaggerCliComponent.create().cli();
	}

	private static void collect(CommandLine command, List<CommandLine> out) {
		out.add(command);
		for (CommandLine sub : command.getSubcommands().values()) {
			collect(sub, out);
		}
	}

	@Test
	@DisplayName("every command in the tree is built by Dagger with its dependencies injected")
	void testAllCommandsAreInjected() {
		List<CommandLine> all = new ArrayList<>();
		collect(cli(), all);

		List<String> notInjected = new ArrayList<>();
		for (CommandLine command : all) {
			Object userObject = command.getCommandSpec().userObject();
			if (!(userObject instanceof io.metaloom.cli.cmd.AbstractCliCommand cmd)) {
				// The root and picocli's own generated completion command are not ours.
				continue;
			}
			// context is @Inject-ed; a reflectively constructed instance would leave it null.
			if (cmd.contextForTest() == null) {
				notInjected.add(command.getCommandName());
			}
		}

		assertThat(notInjected)
			.as("these commands were built without Dagger - add an @IntoMap binding in CommandModule")
			.isEmpty();
	}

	@Test
	@DisplayName("the expected top-level commands are present")
	void testTopLevelCommands() {
		assertThat(cli().getSubcommands().keySet()).contains(
			"login", "logout", "whoami", "config", "pipeline", "run",
			"health", "version", "space", "library", "pool", "user", "group", "role", "completion");
	}

	@Test
	@DisplayName("pipeline and run expose the documented subcommands")
	void testSubcommands() {
		CommandLine cli = cli();

		assertThat(cli.getSubcommands().get("pipeline").getSubcommands().keySet())
			.contains("list", "get", "delete", "run");
		assertThat(cli.getSubcommands().get("run").getSubcommands().keySet())
			.contains("list", "get", "items", "follow", "pause", "resume", "cancel", "stats");
	}

	@Test
	@DisplayName("start and stop are aliases for resume and cancel")
	void testRunAliases() {
		// The requested feature is "pause / start / stop"; the REST vocabulary is
		// pause/resume/cancel. Both spellings have to work.
		var runSubcommands = cli().getSubcommands().get("run").getSubcommands();

		assertThat(runSubcommands).containsKey("start");
		assertThat(runSubcommands.get("start").getCommandName()).isEqualTo("resume");
		assertThat(runSubcommands).containsKey("stop");
		assertThat(runSubcommands.get("stop").getCommandName()).isEqualTo("cancel");
	}

	@Test
	@DisplayName("ls is an alias for list wherever list exists")
	void testListAliases() {
		CommandLine cli = cli();

		assertThat(cli.getSubcommands().get("pipeline").getSubcommands()).containsKey("ls");
		assertThat(cli.getSubcommands().get("user").getSubcommands()).containsKey("ls");
		assertThat(cli.getSubcommands().get("space").getSubcommands()).containsKey("ls");
	}

	@Test
	@DisplayName("--help succeeds and does not contact a server")
	void testHelpNeedsNoServer() {
		java.io.StringWriter out = new java.io.StringWriter();
		CommandLine cli = cli();
		cli.setOut(new java.io.PrintWriter(out));

		// Deliberately pointed at a dead address: help must never open a connection.
		int code = cli.execute("--server", "http://127.0.0.1:1", "--help");

		assertThat(code).isZero();
		assertThat(out.toString()).contains("metaloom");
	}

	@Test
	@DisplayName("every subcommand accepts --help")
	void testEveryCommandHasHelp() {
		// `metaloom pipeline --help` used to fail with "Unknown option": only the root
		// declared mixinStandardHelpOptions. Help is now attached programmatically to the
		// whole tree, so a newly added command cannot miss it.
		List<CommandLine> all = new ArrayList<>();
		collect(cli(), all);

		List<String> missing = new ArrayList<>();
		for (CommandLine command : all) {
			boolean hasHelp = command.getCommandSpec().options().stream()
				.anyMatch(option -> List.of(option.names()).contains("--help"));
			if (!hasHelp) {
				missing.add(command.getCommandName());
			}
		}

		assertThat(missing).as("these commands do not accept --help").isEmpty();
	}

	@Test
	@DisplayName("--help on a nested command succeeds and prints that command's usage")
	void testNestedHelp() {
		java.io.StringWriter out = new java.io.StringWriter();
		CommandLine cli = cli();
		cli.setOut(new java.io.PrintWriter(out));

		int code = cli.execute("pipeline", "run", "--help");

		assertThat(code).isZero();
		assertThat(out.toString()).contains("--dir").contains("--follow");
	}

	@Test
	@DisplayName("a malformed config file is reported cleanly instead of a raw stack trace")
	void testMalformedConfigIsHandled(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
		throws Exception {

		// The config is read by the execution strategy, which runs before picocli's exception
		// handler is reachable - so without explicit handling this escaped as an
		// UncheckedIOException and exit 1.
		java.nio.file.Path bad = tempDir.resolve("bad.yml");
		java.nio.file.Files.writeString(bad, "profiles: [this is not a map\n");

		java.io.StringWriter err = new java.io.StringWriter();
		CommandLine cli = cli();
		cli.setErr(new java.io.PrintWriter(err));

		int code = cli.execute("--config", bad.toString(), "version", "--client");

		assertThat(code).isEqualTo(ExitCode.FILE_ERROR);
		assertThat(err.toString()).contains("configuration").doesNotContain("UncheckedIOException");
	}

	@Test
	@DisplayName("an unknown subcommand is a usage error, not a crash")
	void testUnknownSubcommand() {
		java.io.StringWriter err = new java.io.StringWriter();
		CommandLine cli = cli();
		cli.setErr(new java.io.PrintWriter(err));

		int code = cli.execute("pipeline", "frobnicate");

		assertThat(code).isEqualTo(ExitCode.USAGE);
	}

	@Test
	@DisplayName("an invalid output format is rejected as a usage error")
	void testInvalidOutputFormat() {
		java.io.StringWriter err = new java.io.StringWriter();
		CommandLine cli = cli();
		cli.setErr(new java.io.PrintWriter(err));

		int code = cli.execute("-o", "xml", "version", "--client");

		assertThat(code).isEqualTo(ExitCode.USAGE);
		assertThat(err.toString()).contains("table, json, yaml");
	}
}
