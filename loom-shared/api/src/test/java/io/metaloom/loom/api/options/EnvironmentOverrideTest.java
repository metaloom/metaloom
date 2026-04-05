package io.metaloom.loom.api.options;

import static java.lang.System.getenv;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

public class EnvironmentOverrideTest {

	static final String JSON_TEST_DATA = "{\"test\": 123, \"test2\": \"some content\"}";
	static Map<String, ValueEntry> valuesMap = new HashMap<>();
	static {
		valuesMap.put(TestOptions.STRING_VALUE_ENV, new ValueEntry("test", "test"));
		valuesMap.put(TestOptions.BOOLEAN_VALUE_ENV, new ValueEntry("true", Boolean.TRUE));
		valuesMap.put(TestOptions.BOOLEAN_VALUE_PRIMITIVE_ENV, new ValueEntry("false", false));
		valuesMap.put(TestOptions.DOUBLE_VALUE_ENV, new ValueEntry("0.123", Double.valueOf("0.123")));
		valuesMap.put(TestOptions.DOUBLE_VALUE_PRIMITIVE_ENV, new ValueEntry("0.123", 0.123D));
		valuesMap.put(TestOptions.LONG_VALUE_ENV, new ValueEntry("1234567", Long.valueOf("1234567")));
		valuesMap.put(TestOptions.LONG_VALUE_PRIMITIVE_ENV, new ValueEntry("1234567", 1234567L));
		valuesMap.put(TestOptions.INTEGER_VALUE_ENV, new ValueEntry("1234567", Integer.valueOf("1234567")));
		valuesMap.put(TestOptions.INTEGER_VALUE_PRIMITIVE_ENV, new ValueEntry("1234567", 1234567));
		valuesMap.put(TestOptions.FLOAT_VALUE_ENV, new ValueEntry("0.123", Float.valueOf("0.123")));
		valuesMap.put(TestOptions.FLOAT_VALUE_PRIMITIVE_ENV, new ValueEntry("0.123", 0.123F));
		valuesMap.put(TestOptions.JSON_OBJECT_ENV, new ValueEntry(JSON_TEST_DATA, new JsonObject(JSON_TEST_DATA)));
		valuesMap.put(TestOptions.STRING_LIST_ENV, new ValueEntry("a,b,c", Arrays.asList("a", "b", "c")));
		valuesMap.put(TestOptions.STRING_SET_ENV, new ValueEntry("a,b,c", new HashSet<>(Arrays.asList("a", "b", "c"))));
	}

	@BeforeAll
	public static void setEnvironmentVariables() {
		valuesMap.forEach((key, entry) -> {
			getEditableMapOfVariables().put(key, entry.stringValue);
		});
		
		for(Entry<String, String> e : System.getenv().entrySet()) {
			System.out.println(e.getKey() + " = " + e.getValue());
		}
	}

	private void assertValues(Map<String, Object> values) {
		valuesMap.forEach((key, entry) -> assertEquals(entry.expectedValue, values.get(key), key + " does not match"));
	}

	@Test
	public void testSetOptionField() throws Exception {
		TestOptions options = new TestOptions();
		options.overrideWithEnv();
		assertValues(options.getValues());
	}

	@Test
	public void testSetViaSetter() {
		TestMethodSetOption options = new TestMethodSetOption();
		options.overrideWithEnv();
		assertValues(options.values);
	}

	private static Map<String, String> getEditableMapOfVariables() {
		Class<?> classOfMap = getenv().getClass();
		try {
			return getFieldValue(classOfMap, getenv(), "m");
		} catch (IllegalAccessException e) {
			throw new RuntimeException("System Rules cannot access the field"
				+ " 'm' of the map System.getenv().", e);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException("System Rules expects System.getenv() to"
				+ " have a field 'm' but it has not.", e);
		}
	}

	private static Map<String, String> getFieldValue(
		Class<?> klass,
		Object object,
		String name) throws NoSuchFieldException, IllegalAccessException {
		Field field = klass.getDeclaredField(name);
		field.setAccessible(true);
		return (Map<String, String>) field.get(object);
	}

}
