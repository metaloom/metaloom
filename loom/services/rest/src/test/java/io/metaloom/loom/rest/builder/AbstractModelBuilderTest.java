package io.metaloom.loom.rest.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.mockito.Mockito;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.Element;
import io.metaloom.loom.db.MetaElement;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.builder.impl.LoomModelBuilderImpl;
import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;
import io.metaloom.loom.test.data.TestValues;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public abstract class AbstractModelBuilderTest implements TestValues {

	/**
	 * Set to true to overwrite the stored models with the currently built output instead of asserting against them.
	 */
	public static final String UPDATE_MODELS_PROP = "model.update";

	private static final Path MODEL_SOURCE_DIR = Paths.get("src", "test", "resources", "model");

	public LoomModelBuilder builder() {
		// RETURNS_MOCKS yields a stub for every dao accessor and empty collections for their
		// list-returning methods, so builders that reach for additional daos don't NPE here.
		DaoCollection daos = mock(DaoCollection.class, Mockito.RETURNS_MOCKS);
		LoomModelValidator validator = new LoomModelValidatorImpl();
		UserDao userDao = mock(UserDao.class);
		User user = mock(User.class);
		when(user.getUsername()).thenReturn("creatorEditor");
		when(user.getUuid()).thenReturn(USER_UUID);
		when(userDao.load(Mockito.any())).thenReturn(user);
		when(daos.userDao()).thenReturn(userDao);
		LoomModelBuilder builder = new LoomModelBuilderImpl(daos, validator);
		return builder;
	}

	public void assertWithModel(RestModel model, String modelName) throws IOException {
		String json = LoomJson.encode(model);
		assertWithModel(json, modelName);
	}

	public void assertWithModel(String json, String modelName) throws IOException {
		if (Boolean.getBoolean(UPDATE_MODELS_PROP)) {
			Path target = MODEL_SOURCE_DIR.resolve(modelName);
			Files.createDirectories(target.getParent());
			Files.writeString(target, json, Charset.defaultCharset());
			System.out.println("Updated model file " + target);
			return;
		}
		try (InputStream ins = getClass().getResourceAsStream("/model/" + modelName)) {
			assertNotNull(ins, "Model file " + modelName + " not found in test resources."
				+ " Re-run with -D" + UPDATE_MODELS_PROP + "=true to regenerate the stored models.");
			String model = IOUtils.toString(ins, Charset.defaultCharset());
			assertEquals(model, json, "The json did not match with the stored model."
				+ " Re-run with -D" + UPDATE_MODELS_PROP + "=true to regenerate the stored models.");
		}
	}

	public void mockCreatorEditor(CUDElement<?> element) {
		Mockito.when(element.getCreated()).thenReturn(DATE_OLD);
		Mockito.when(element.getEdited()).thenReturn(DATE_OLD);
		Mockito.when(element.getCreatorUuid()).thenReturn(USER_UUID);
		Mockito.when(element.getEditorUuid()).thenReturn(USER_UUID);
	}

	public void mockMeta(MetaElement<?> element) {
		JsonObject json = new JsonObject().put("abc", "efg");
		json.put("nested", new JsonObject().put("key", 42));
		json.put("array", new JsonArray().add(42).add(24));
		Mockito.when(element.getMeta()).thenReturn(json);
	}

	public User mockUser(String username) {
		User user = mock(User.class);
		when(user.getUuid()).thenReturn(USER_UUID);
		when(user.getUsername()).thenReturn(username);
		return user;
	}

	public <T extends Element<T>> Page<T> mockPage(T... elements) {
		List<T> list = new ArrayList<>();
		for (T element : elements) {
			list.add(element);
		}
		Page<T> page = new Page<>(25, list);
		return page;
	}

	abstract void testResponseModel() throws IOException;

	abstract void testListResponseModel() throws IOException;

}
