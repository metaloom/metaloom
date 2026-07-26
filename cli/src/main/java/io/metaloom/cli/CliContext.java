package io.metaloom.cli;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.output.ColorMode;
import io.metaloom.cli.output.OutputFormat;

/**
 * The resolved global settings for one CLI invocation.
 *
 * <p>A mutable singleton, populated in two passes: picocli's global option setters write the
 * flags as they are parsed, then {@code CliExecutionStrategy} fills the gaps from the
 * environment, the config file and the defaults - before any command runs.</p>
 *
 * <p>This is what lets the CLI avoid the two-pass parse that {@code CortexCLIMain} needs.
 * Dagger can build the whole object graph up front because nothing in it reads this context
 * at construction time; commands take a {@code Provider} and read it when they execute.</p>
 */
@Singleton
public class CliContext {

	public static final String DEFAULT_SERVER = "http://localhost:6333";

	private String serverUrl;
	private String profile;
	private String token;
	private Path tokenFile;
	private OutputFormat output;
	private boolean quiet;
	private int verbosity;
	private ColorMode colorMode;
	private Duration timeout;
	private Path configFile;
	private boolean insecure;

	@Inject
	public CliContext() {
	}

	// The raw flag values. Null means "not given on the command line", which is what lets
	// the execution strategy tell an explicit `--output table` from the default.

	public String getServerUrl() {
		return serverUrl;
	}

	public void setServerUrl(String serverUrl) {
		this.serverUrl = serverUrl;
	}

	public String getProfile() {
		return profile;
	}

	public void setProfile(String profile) {
		this.profile = profile;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public Path getTokenFile() {
		return tokenFile;
	}

	public void setTokenFile(Path tokenFile) {
		this.tokenFile = tokenFile;
	}

	public OutputFormat getOutput() {
		return output == null ? OutputFormat.TABLE : output;
	}

	public void setOutput(OutputFormat output) {
		this.output = output;
	}

	public boolean isQuiet() {
		return quiet;
	}

	public void setQuiet(boolean quiet) {
		this.quiet = quiet;
	}

	public int getVerbosity() {
		return verbosity;
	}

	public void setVerbosity(int verbosity) {
		this.verbosity = verbosity;
	}

	public ColorMode getColorMode() {
		return colorMode == null ? ColorMode.AUTO : colorMode;
	}

	public void setColorMode(ColorMode colorMode) {
		this.colorMode = colorMode;
	}

	public Duration getTimeout() {
		return timeout == null ? Duration.ofSeconds(30) : timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public Path getConfigFile() {
		return configFile;
	}

	public void setConfigFile(Path configFile) {
		this.configFile = configFile;
	}

	public boolean isInsecure() {
		return insecure;
	}

	public void setInsecure(boolean insecure) {
		this.insecure = insecure;
	}

	/** @return the effective server URL, never null */
	public URI serverUri() {
		return URI.create(serverUrl == null || serverUrl.isBlank() ? DEFAULT_SERVER : serverUrl);
	}

	/** @return the effective profile name, never null */
	public String profileName() {
		return profile == null || profile.isBlank() ? "default" : profile;
	}
}
