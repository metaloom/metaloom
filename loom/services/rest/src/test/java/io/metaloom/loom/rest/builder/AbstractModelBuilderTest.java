package io.metaloom.loom.rest.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
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

	public LoomModelBuilder builder() {
		DaoCollection daos = mock(DaoCollection.class);
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
		try (InputStream ins = getClass().getResourceAsStream("/model/" + modelName)) {
			if (ins == null) {
				System.out.println(json);
			}
			assertNotNull(ins, "Model file " + modelName + " not found in test resources");
			String model = IOUtils.toString(ins, Charset.defaultCharset());
			assertEquals(model, json, "The json did not match with the stored model.");
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
