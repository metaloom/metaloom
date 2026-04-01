package io.metaloom.loom.rest.model.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import org.apache.commons.io.IOUtils;

import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.RestModel;

public final class ModelTestHelper {

	/**
	 * Assert that the given model matches the reference file.
	 * 
	 * @param model
	 *            Model to be asserted
	 * @param name
	 *            Name of the reference JSON file
	 */
	public static void assertModel(RestModel model, String name) {
		InputStream ins = ModelTestHelper.class.getResourceAsStream("/model/" + name + ".json");
		if (ins == null) {
			fail("Model json file for " + name + " could not be found in resources.");
		}
		try {
			String referenceJson = IOUtils.toString(ins, Charset.defaultCharset());
			String modelJson = LoomJson.encode(model);
			if (!modelJson.equals(referenceJson)) {
				System.out.println(modelJson);
			}
			assertEquals(referenceJson, modelJson, "The JSON of the model did not match with the reference: " + name);
		} catch (IOException e) {
			e.printStackTrace();
			fail("Could not read resource");
		} finally {
			try {
				ins.close();
			} catch (IOException e) {
				e.printStackTrace();
				fail("Failed to close resource stream");
			}
		}

	}
}
