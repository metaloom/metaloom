package io.metaloom.cli.output;

import java.io.PrintWriter;
import java.util.List;
import java.util.function.Function;

/**
 * Renders command results.
 *
 * <p><strong>Stream discipline.</strong> Machine-readable output goes to stdout and nothing
 * else ever does; progress, warnings and prompts go to stderr. That is what makes
 * {@code metaloom -o json pipeline list | jq} reliable - a stray "Loading..." on stdout
 * would break every pipe.</p>
 *
 * <p><strong>Quiet mode</strong> reduces a table to bare identifiers, one per line, so
 * {@code metaloom -q pipeline list | xargs -n1 metaloom pipeline get} works. It has no
 * effect on JSON or YAML, which are already machine-readable.</p>
 */
public class Printer {

	private final PrintWriter out;
	private final PrintWriter err;
	private final OutputFormat format;
	private final boolean quiet;
	private final Ansi ansi;

	public Printer(PrintWriter out, PrintWriter err, OutputFormat format, boolean quiet, Ansi ansi) {
		this.out = out;
		this.err = err;
		this.format = format;
		this.quiet = quiet;
		this.ansi = ansi;
	}

	public OutputFormat format() {
		return format;
	}

	public Ansi ansi() {
		return ansi;
	}

	public boolean isQuiet() {
		return quiet;
	}

	public PrintWriter out() {
		return out;
	}

	/**
	 * Render a list of items.
	 *
	 * @param items      the items, serialized as-is for JSON/YAML
	 * @param table      builds the human-readable table
	 * @param identifier extracts the one value quiet mode prints per item
	 */
	public <T> void printList(List<T> items, Function<List<T>, Table> table, Function<T, String> identifier) {
		switch (format) {
			case JSON -> writeSerialized(CliJson.json(), items);
			case YAML -> writeSerialized(CliJson.yaml(), items);
			case TABLE -> {
				if (quiet) {
					for (T item : items) {
						out.println(identifier.apply(item));
					}
					return;
				}
				if (items.isEmpty()) {
					// To stderr: an empty result is not data, and printing it on stdout would
					// put a human-readable sentence into a machine-readable stream.
					err.println("No results.");
					return;
				}
				out.print(table.apply(items).render(ansi));
			}
		}
		out.flush();
	}

	/**
	 * Render a single object.
	 *
	 * @param item       the object
	 * @param table      builds the human-readable view
	 * @param identifier extracts the one value quiet mode prints
	 */
	public <T> void printOne(T item, Function<T, Table> table, Function<T, String> identifier) {
		switch (format) {
			case JSON -> writeSerialized(CliJson.json(), item);
			case YAML -> writeSerialized(CliJson.yaml(), item);
			case TABLE -> {
				if (quiet) {
					out.println(identifier.apply(item));
					return;
				}
				out.print(table.apply(item).render(ansi));
			}
		}
		out.flush();
	}

	/**
	 * Report a successful action that produces no data (pause, resume, delete).
	 *
	 * <p>Goes to stderr in table mode: it is a status message, not a result. In JSON/YAML
	 * mode it becomes a real object on stdout, so scripts can assert on it.</p>
	 */
	public void printMessage(String message) {
		switch (format) {
			case JSON -> writeSerialized(CliJson.json(), java.util.Map.of("message", message));
			case YAML -> writeSerialized(CliJson.yaml(), java.util.Map.of("message", message));
			case TABLE -> {
				if (!quiet) {
					err.println(message);
				}
			}
		}
		out.flush();
	}

	/** A warning. Always stderr, always suppressed by {@code --quiet}. */
	public void warn(String message) {
		if (!quiet) {
			err.println(ansi.yellow("warning: ") + message);
		}
	}

	/** Progress chatter. Always stderr, always suppressed by {@code --quiet}. */
	public void info(String message) {
		if (!quiet) {
			err.println(message);
		}
	}

	/** An error. Always stderr, never suppressed - {@code --quiet} hides noise, not failures. */
	public void error(String message) {
		err.println(ansi.red("error: ") + message);
		err.flush();
	}

	/** Write one compact JSON object plus newline - the NDJSON event stream. */
	public void printNdjson(Object value) {
		try {
			out.println(CliJson.ndjson().writeValueAsString(value));
			out.flush();
		} catch (Exception e) {
			error("Could not serialize event: " + e.getMessage());
		}
	}

	/** Write one YAML document, separated by `---` so a stream stays parseable. */
	public void printYamlDocument(Object value) {
		try {
			out.println("---");
			out.print(CliJson.yaml().writeValueAsString(value));
			out.flush();
		} catch (Exception e) {
			error("Could not serialize event: " + e.getMessage());
		}
	}

	private void writeSerialized(com.fasterxml.jackson.databind.ObjectMapper mapper, Object value) {
		try {
			out.println(mapper.writeValueAsString(value));
		} catch (Exception e) {
			error("Could not serialize output: " + e.getMessage());
		}
	}
}
