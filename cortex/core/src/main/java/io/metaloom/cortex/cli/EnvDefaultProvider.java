package io.metaloom.cortex.cli;

import java.util.HashMap;
import java.util.Map;

import picocli.CommandLine.IDefaultValueProvider;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Picocli default value provider that resolves values from environment variables.
 */
public class EnvDefaultProvider implements IDefaultValueProvider {

	private static final Map<String, String> OPTION_ENV_MAP = new HashMap<>();

	static {
		OPTION_ENV_MAP.put("--hostname", "LOOM_HOST");
		OPTION_ENV_MAP.put("--port", "LOOM_PORT");
		OPTION_ENV_MAP.put("--monitoring-port", "CORTEX_MONITORING_PORT");
		OPTION_ENV_MAP.put("--meta-path", "CORTEX_META_PATH");
	}

	@Override
	public String defaultValue(ArgSpec argSpec) throws Exception {
		if (argSpec instanceof OptionSpec) {
			OptionSpec option = (OptionSpec) argSpec;
			String envVar = OPTION_ENV_MAP.get(option.longestName());
			if (envVar != null) {
				return System.getenv(envVar);
			}
		}
		return null;
	}
}
