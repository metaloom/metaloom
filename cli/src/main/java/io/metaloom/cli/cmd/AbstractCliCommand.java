package io.metaloom.cli.cmd;

import java.util.concurrent.Callable;

import javax.inject.Inject;

import io.metaloom.cli.CliContext;
import io.metaloom.cli.ExitCode;
import io.metaloom.cli.client.CliException;
import io.metaloom.cli.client.LoomApi;
import io.metaloom.cli.output.Ansi;
import io.metaloom.cli.output.Printer;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Shared plumbing for every command.
 *
 * <p>Commands implement {@link #execute()} and either return an exit code or throw a
 * {@link CliException}; they never print errors or call {@code System.exit} themselves.</p>
 *
 * <p>The output writers come from {@code CommandSpec} rather than {@code System.out}, which
 * is what lets a test capture output by calling {@code commandLine.setOut(...)}.</p>
 */
// @Command on the base is what lets picocli's annotation processor accept the inherited
// @Spec and @Option members; concrete subclasses re-declare it with their own name.
@picocli.CommandLine.Command
public abstract class AbstractCliCommand implements Callable<Integer> {

	@Spec
	protected CommandSpec spec;

	@Inject
	protected CliContext context;

	@Inject
	protected javax.inject.Provider<LoomApi> apiProvider;

	private Printer printer;

	/** The API client. Resolved lazily so `--help` never opens a connection. */
	protected LoomApi api() {
		return apiProvider.get();
	}

	public Printer printer() {
		if (printer == null) {
			boolean tty = System.console() != null;
			Ansi ansi = Ansi.resolve(context.getColorMode(), System::getenv, tty);
			printer = new Printer(spec.commandLine().getOut(), spec.commandLine().getErr(),
				context.getOutput(), context.isQuiet(), ansi);
		}
		return printer;
	}

	@Override
	public Integer call() {
		return execute();
	}

	/**
	 * Run the command.
	 *
	 * @return the exit code
	 */
	protected abstract Integer execute();

	/**
	 * @return the injected context, or null if this instance was not built by Dagger
	 *         (which {@code CliCommandTreeTest} uses to catch a missing binding)
	 */
	public CliContext contextForTest() {
		return context;
	}

	/** Show usage and fail: what a command group does when given no subcommand. */
	protected Integer usage() {
		spec.commandLine().usage(spec.commandLine().getOut());
		return ExitCode.USAGE;
	}
}
