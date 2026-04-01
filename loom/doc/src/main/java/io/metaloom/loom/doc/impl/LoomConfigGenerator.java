package io.metaloom.loom.doc.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.FileUtils;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.common.options.LoomOptionsLoader;
import io.metaloom.loom.doc.Generator;

public class LoomConfigGenerator implements Generator {

	@Override
	public void generate() throws JsonProcessingException, IOException {
		LoomOptions options = LoomOptionsLoader.defaultLoomConfig();
		File confFile = new File("src/main/generated/loom-config.yaml");
		FileUtils.writeStringToFile(confFile, LoomOptionsLoader.getYAMLMapper().writeValueAsString(options), StandardCharsets.UTF_8, false);
	}

}
