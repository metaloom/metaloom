package io.metaloom.loom.doc.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.FileUtils;

import io.metaloom.loom.doc.Generator;
import io.metaloom.loom.rest.openapi.LoomOpenAPI;

public class OpenAPIGenerator implements Generator {

	@Override
	public void generate() throws IOException {
		LoomOpenAPI  api = new LoomOpenAPI();
		String json = api.generateJson();
		File confFile = new File("src/main/generated/openapi.json");
		FileUtils.writeStringToFile(confFile, json, StandardCharsets.UTF_8, false);
	}

}
