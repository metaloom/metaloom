package io.metaloom.loom.common.options;

import static io.metaloom.loom.api.LoomEnv.HOME_CONFIG_PATH;
import static io.metaloom.loom.api.LoomEnv.*;
import static io.metaloom.loom.api.LoomEnv.LOOM_CONF_FILENAME;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.LoomEnv;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.utils.StringUtils;

public final class LoomOptionsLoader {

	private static final Logger log = LoggerFactory.getLogger(LoomOptionsLoader.class);

	private LoomOptionsLoader() {

	}

	/**
	 * Return the preconfigured object mapper which is used to transform YAML documents.
	 * 
	 * @return
	 */
	public static ObjectMapper getYAMLMapper() {
		YAMLFactory factory = new YAMLFactory();
		ObjectMapper mapper = new ObjectMapper(factory);
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		mapper.setSerializationInclusion(Include.ALWAYS);
		return mapper;
	}

	public static LoomOptionsLookup createOrLoadOptions() {
		LoomOptionsLookup lookup = loadLoomOptions();
		// applyNonYamlProperties(defaultOption, options);
		// applyEnvironmentVariables(options);
		// applyCommandLineArgs(options, args);
		lookup.options().validate();
		return lookup;
	}

	/**
	 * Try to load the loom options from different locations (config folder). Otherwise a default configuration will be generated.
	 * 
	 * @return
	 */
	private static LoomOptionsLookup loadLoomOptions() {

		// Lookup order to local files
		List<Path> configLookupOrder = List.of(LOCAL_ETC_PATH, HOME_CONFIG_PATH, LOCAL_CONFIG_PATH);
		for (Path confPath : configLookupOrder) {
			Optional<LoomOptions> etcConf = loadFromPath(confPath);
			if (etcConf.isPresent()) {
				LoomOptions options = etcConf.get();
				File baseFolder = confPath.toFile().getParentFile().getAbsoluteFile();
				return new LoomOptionsLookup(baseFolder, options);
			}
		}

		// Try to load from classpath
		LoomOptions options = null;
		InputStream ins = Loom.class.getResourceAsStream("/" + LOOM_CONF_FILENAME);
		if (ins != null) {
			log.info("Loading configuration file from classpath.");
			options = loadConfiguration(ins);
			if (options != null) {
				return new LoomOptionsLookup(null, options);
			} else {
				throw new RuntimeException("Could not read configuration file");
			}
		} else {
			log.info("Configuration file {" + LOOM_CONF_FILENAME + "} was not found within classpath.");
		}

		ObjectMapper mapper = getYAMLMapper();
		File localConfigFile = LoomEnv.LOCAL_CONFIG_PATH.toFile();
		File parentFolder = localConfigFile.getParentFile();
		try {
			if (!parentFolder.exists() && !parentFolder.mkdirs()) {
				throw new RuntimeException("Failed to create config folder " + localConfigFile.getParentFile().getAbsolutePath());
			}
			// Generate default config
			options = generateDefaultConfig();
			FileUtils.writeStringToFile(localConfigFile, mapper.writeValueAsString(options), StandardCharsets.UTF_8, false);
			log.info("Saved default configuration to file {}.", localConfigFile.getAbsolutePath());
		} catch (IOException e) {
			log.error("Error while saving default configuration to file {" + localConfigFile.getAbsolutePath() + "}.", e);
		}
		// No luck - use default config
		log.info("Loading default configuration.");
		return new LoomOptionsLookup(LoomEnv.LOCAL_CONFIG_PATH.toFile().getParentFile().getAbsoluteFile(), options);

	}

	private static Optional<LoomOptions> loadFromPath(Path confPath) {
		if (Files.exists(confPath)) {
			try {
				log.info("Loading configuration file {" + confPath + "}.");
				try (FileInputStream fis = new FileInputStream(confPath.toFile())) {
					LoomOptions configuration = loadConfiguration(fis);
					return Optional.ofNullable(configuration);
				}
			} catch (IOException e) {
				log.error("Could not load configuration file {" + confPath + "}.", e);
			}
			return Optional.empty();
		} else {
			log.info("No config found at {}", confPath);
			return Optional.empty();
		}
	}

	/**
	 * Load the configuration from the stream.
	 * 
	 * @param ins
	 * @return
	 */
	private static LoomOptions loadConfiguration(InputStream ins) {
		if (ins == null) {
			log.info("Config file {" + LOOM_CONF_FILENAME + "} not found. Using default configuration.");
			return defaultLoomConfig();
		}

		ObjectMapper mapper = getYAMLMapper();
		try {
			return mapper.readValue(ins, LoomOptions.class);
		} catch (Exception e) {
			log.error("Could not parse configuration.", e);
			throw new RuntimeException("Could not parse options file", e);
		}
	}

	public static LoomOptions defaultLoomConfig() {
		LoomOptions options = new LoomOptions();
		return options;
	}

	/**
	 * Generate a default configuration with meaningful default settings.
	 * 
	 * @return
	 */
	public static LoomOptions generateDefaultConfig() {
		LoomOptions options = new LoomOptions();
		options.getAuth().setKeystorePassword(StringUtils.randomHumanString(12));
		// options.setNodeName(LoomNameProvider.getInstance().getRandomName());
		return options;
	}

}
