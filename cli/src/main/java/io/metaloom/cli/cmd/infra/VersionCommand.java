package io.metaloom.cli.cmd.infra;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.CliVersion;
import io.metaloom.cli.ExitCode;
import io.metaloom.cli.client.CliException;
import io.metaloom.cli.cmd.AbstractCliCommand;
import io.metaloom.cli.output.Table;
import io.metaloom.loom.rest.model.info.RESTInfoResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Report the CLI version and, unless told otherwise, the server's.
 *
 * <p>The server lookup is best-effort: {@code metaloom version} has to work when the server
 * is down, which is exactly when someone runs it.</p>
 */
@Singleton
@Command(name = "version", description = "Show the CLI and server versions.")
public class VersionCommand extends AbstractCliCommand {

	@Option(names = "--client", description = "Only report the CLI version; do not contact the server.")
	boolean clientOnly;

	@Inject
	public VersionCommand() {
	}

	@Override
	protected Integer execute() {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("client", CliVersion.version());

		if (!clientOnly) {
			try {
				RESTInfoResponse info = api().info();
				values.put("server", info.getVersion());
				// Only when the server reports one: an empty row reads like a missing value
				// rather than a field the server does not populate.
				if (info.getDbRevision() != null && !info.getDbRevision().isBlank()) {
					values.put("dbRevision", info.getDbRevision());
				}
			} catch (CliException e) {
				values.put("server", "unreachable");
				printer().warn("Could not reach the server: " + e.getMessage());
			}
		}

		printer().printOne(values,
			map -> {
				Table table = new Table("COMPONENT", "VERSION");
				map.forEach(table::row);
				return table;
			},
			map -> map.get("client"));
		return ExitCode.OK;
	}
}
