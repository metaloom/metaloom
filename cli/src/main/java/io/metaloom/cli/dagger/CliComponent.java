package io.metaloom.cli.dagger;

import javax.inject.Singleton;

import dagger.Component;
import io.metaloom.cli.CliContext;
import picocli.CommandLine;

/**
 * The CLI object graph.
 */
@Singleton
@Component(modules = { CliModule.class, CommandModule.class, PicoCLIModule.class })
public interface CliComponent {

	CommandLine cli();

	CliContext context();
}
