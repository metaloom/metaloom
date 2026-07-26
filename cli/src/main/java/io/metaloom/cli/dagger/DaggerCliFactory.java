package io.metaloom.cli.dagger;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import picocli.CommandLine.IFactory;

/**
 * Lets picocli construct command objects through Dagger.
 *
 * <p>This is what allows the command tree to be declared with
 * {@code @Command(subcommands = {...})} annotations while every node is still a
 * Dagger-managed singleton with its dependencies injected. The alternative - the
 * {@code @Provides CommandLine} + {@code addSubcommand(...)} pattern used by
 * {@code cortex/cli} - means enumerating every command by hand in a provider method, which
 * does not scale past a handful.</p>
 *
 * <p>Anything not in the map falls back to picocli's own factory, which is how
 * picocli-owned types (type converters, {@code AutoComplete.GenerateCompletion}) keep
 * working.</p>
 */
@Singleton
public class DaggerCliFactory implements IFactory {

	private final Map<Class<?>, Provider<Object>> commands;
	private final IFactory fallback = picocli.CommandLine.defaultFactory();

	@Inject
	public DaggerCliFactory(Map<Class<?>, Provider<Object>> commands) {
		this.commands = commands;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <K> K create(Class<K> cls) throws Exception {
		Provider<Object> provider = commands.get(cls);
		if (provider != null) {
			return (K) provider.get();
		}
		return fallback.create(cls);
	}
}
