package io.metaloom.cli.dagger;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.CliContext;
import io.metaloom.cli.ExitCode;
import io.metaloom.cli.client.CliException;
import io.metaloom.cli.config.CliConfigLoader;
import picocli.CommandLine.IExecutionStrategy;
import picocli.CommandLine.ParseResult;

/**
 * Settles the global configuration between parsing and executing.
 *
 * <p>Sits in the one place that knows both what the user typed and what the command is about
 * to do. That matters for precedence: {@link ParseResult#hasMatchedOption(String)} answers
 * "was this flag actually given?", which a null check on the context cannot - once a default
 * has been applied, "unset" and "explicitly set to the default" look identical.</p>
 *
 * <p>Doing it here rather than in {@code main} is what removes the need for the throwaway
 * first parse that {@code CortexCLIMain} performs.</p>
 */
@Singleton
public class CliExecutionStrategy implements IExecutionStrategy {

	private final CliContext context;
	private final CliConfigLoader configLoader;

	@Inject
	public CliExecutionStrategy(CliContext context, CliConfigLoader configLoader) {
		this.context = context;
		this.configLoader = configLoader;
	}

	@Override
	public int execute(ParseResult parseResult) {
		try {
			// Ask the whole command chain, not just the leaf: an inherited option matched on
			// the root is not recorded as matched on the subcommand.
			configLoader.resolve(context, option -> matchedAnywhere(parseResult, option));
		} catch (Exception e) {
			// This runs before picocli's execution-exception handler is reached, so an
			// unreadable or malformed config file would otherwise escape as a raw stack trace
			// with exit 1. Report it the same way every other failure is reported.
			CliException failure = new CliException(ExitCode.FILE_ERROR,
				"Could not read the configuration: " + rootMessage(e), null, e);
			java.io.PrintWriter err = parseResult.commandSpec().commandLine().getErr();
			err.println("error: " + failure.getMessage());
			if (context.getVerbosity() > 0) {
				failure.printStackTrace(err);
			}
			err.flush();
			return failure.getExitCode();
		}
		applyLogLevel();
		return new picocli.CommandLine.RunLast().execute(parseResult);
	}

	private static String rootMessage(Throwable t) {
		Throwable current = t;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		String message = current.getMessage();
		return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
	}

	private boolean matchedAnywhere(ParseResult parseResult, String option) {
		ParseResult current = parseResult;
		while (current != null) {
			if (current.hasMatchedOption(option)) {
				return true;
			}
			current = current.subcommand();
		}
		return false;
	}

	/**
	 * Map {@code -v} / {@code --quiet} onto the root log level.
	 *
	 * <p>Done reflectively-but-safely through the SLF4J binding: the CLI depends on logback
	 * at runtime, but should not fail to start if a different binding is on the classpath.</p>
	 */
	private void applyLogLevel() {
		String level = context.isQuiet() ? "ERROR" : switch (context.getVerbosity()) {
			case 0 -> "WARN";
			case 1 -> "INFO";
			default -> "DEBUG";
		};
		System.setProperty("metaloom.cli.loglevel", level);
		try {
			org.slf4j.Logger root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
			if (root instanceof ch.qos.logback.classic.Logger logbackRoot) {
				logbackRoot.setLevel(ch.qos.logback.classic.Level.toLevel(level));
			}
		} catch (NoClassDefFoundError ignored) {
			// Not running on logback; the level stays at whatever the binding defaults to.
		}
	}
}
