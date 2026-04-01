package io.metaloom.loom.rest.model.example;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.RestModel;

public abstract class AbstractModelTest implements ModelTestcases  {

	public String load(String modelName) {
		String path = "/models/" + modelName + ".json";
		try (InputStream ins = getClass().getResourceAsStream(path)) {
			if (ins == null) {
				fail("Could not find model file " + path + " in classpath.");
			}
			return new String(ins.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public <T extends RestModel> T load(String modelName, Class<T> classOfT) {
		String body = load(modelName);
		System.out.println(body);
		return LoomJson.parse(body, classOfT);
	}

}
