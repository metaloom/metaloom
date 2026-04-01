package io.metaloom.loom.rest.openapi;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

public class LoomOpenAPITest {

	@Test
	public void testGenerate() throws JsonProcessingException {
		LoomOpenAPI api = new LoomOpenAPI();
		api.generateJson();
	}
}
