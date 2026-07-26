package io.metaloom.cli.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * One named server configuration.
 */
@JsonInclude(Include.NON_NULL)
public class Profile {

	private String server;
	private String output;
	private String timeout;

	public String getServer() {
		return server;
	}

	public Profile setServer(String server) {
		this.server = server;
		return this;
	}

	public String getOutput() {
		return output;
	}

	public Profile setOutput(String output) {
		this.output = output;
		return this;
	}

	public String getTimeout() {
		return timeout;
	}

	public Profile setTimeout(String timeout) {
		this.timeout = timeout;
		return this;
	}
}
