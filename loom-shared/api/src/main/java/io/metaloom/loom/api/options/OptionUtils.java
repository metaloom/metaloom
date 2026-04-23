package io.metaloom.loom.api.options;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonObject;

public class OptionUtils {

	static final Logger log = LoggerFactory.getLogger(Option.class);
	private static final Pattern SPLIT_PATTERN = Pattern.compile(",");
	private static final String MASK = "********";

	static Function<String, String> envLookup = System::getenv;

	/**
	 * Convert a string value to a type. Throws an runtime exception when the type is not supported or certain conversions fail.
	 * 
	 * @param clazz
	 *            the class of the type to convert to.
	 * @param value
	 *            the string value to convert
	 * @param <T>
	 *            the target type
	 * @return the converted value
	 */
	@SuppressWarnings({ "unchecked", "rawtypes", "unused" })
	private static <T> T convertValue(Class<T> clazz, String value) {
		if ("null".equals(value)) {
			return null;
		} else if (clazz.equals(String.class)) {
			return (T) value;
		} else if (clazz.equals(boolean.class) || clazz.equals(Boolean.class)) {
			return (T) Boolean.valueOf(value);
		} else if (clazz.equals(long.class) || clazz.equals(Long.class)) {
			return (T) Long.valueOf(value);
		} else if (clazz.equals(int.class) || clazz.equals(Integer.class)) {
			return (T) Integer.valueOf(value);
		} else if (clazz.equals(float.class) || clazz.equals(Float.class)) {
			return (T) Float.valueOf(value);
		} else if (clazz.equals(double.class) || clazz.equals(Double.class)) {
			return (T) Double.valueOf(value);
		} else if (clazz.equals(JsonObject.class)) {
			JsonObject obj;
			try {
				return (T) new JsonObject(value);
			} catch (Exception e) {
				throw new RuntimeException("Could not parse credentials env string as JsonObject: " + value, e);
			}
		} else if (clazz.isEnum()) {
			Object enumValue = Enum.valueOf((Class<Enum>) clazz, value);
			return (T) enumValue;
		} else if (clazz.equals(List.class)) {
			if (value == null || value.trim().length() == 0) {
				return (T) Collections.emptyList();
			}
			return (T) SPLIT_PATTERN.splitAsStream(value).collect(Collectors.toList());
		} else if (clazz.equals(Set.class)) {
			if (value == null || value.trim().length() == 0) {
				return (T) Collections.emptySet();
			}
			return (T) SPLIT_PATTERN.splitAsStream(value).collect(Collectors.toSet());
		} else {
			throw new RuntimeException("Could no convert environment variable for type " + clazz.getName());
		}
	}

	/**
	 * Override options with environment variable using an annotated method.
	 * 
	 * @param method
	 *            a method annotated with {@link EnvironmentVariable}
	 * @param target
	 *            the target option object
	 */
	static void overrideWithEnvViaMethod(Method method, Option target) {
		EnvironmentVariable envInfo = method.getAnnotation(EnvironmentVariable.class);
		String name = envInfo.name();
		String value = envLookup.apply(name);
		if (value == null) {
			return;
		}
		Class<?> typeClazz = method.getParameterTypes()[0];
		try {
			log.info("Setting env via method {" + name + "=" + (envInfo.isSensitive() ? MASK : value) + "}");
			method.invoke(target, convertValue(typeClazz, value));
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new RuntimeException("Could not set environment variable via method {" + name + "} with value {" + value + "}", e);
		}
	}

	/**
	 * Override options with environment variable using an annotated field.
	 * 
	 * @param field
	 *            a field annotated with {@link EnvironmentVariable}
	 * @param target
	 *            the target option object
	 */
	static void overrideWitEnvViaFieldSet(Field field, Option target) {
		EnvironmentVariable envInfo = field.getAnnotation(EnvironmentVariable.class);
		String name = envInfo.name();
		String value = envLookup.apply(name);
		if (value == null) {
			return;
		}
		try {
			log.info("Setting env via field access {" + name + "=" + (envInfo.isSensitive() ? MASK : value) + "}");
			field.setAccessible(true);
			field.set(target, convertValue(field.getType(), value));
		} catch (IllegalArgumentException | IllegalAccessException ex) {
			throw new RuntimeException("Could not set environment variable for {" + name + "} with value {" + value + "}", ex);
		}
	}

	// -- Reflection-free helpers for native-image-safe env overrides ----------

	/**
	 * Apply a string environment variable using a direct setter.
	 */
	public static void applyEnv(String envName, Consumer<String> setter) {
		String value = envLookup.apply(envName);
		if (value != null) {
			log.info("Setting env {" + envName + "=" + value + "}");
			setter.accept(value);
		}
	}

	/**
	 * Apply a sensitive string environment variable (value is masked in logs).
	 */
	public static void applyEnvSensitive(String envName, Consumer<String> setter) {
		String value = envLookup.apply(envName);
		if (value != null) {
			log.info("Setting env {" + envName + "=" + MASK + "}");
			setter.accept(value);
		}
	}

	/**
	 * Apply an int environment variable using a direct setter.
	 */
	public static void applyEnvInt(String envName, IntConsumer setter) {
		String value = envLookup.apply(envName);
		if (value != null) {
			log.info("Setting env {" + envName + "=" + value + "}");
			setter.accept(Integer.parseInt(value));
		}
	}

	/**
	 * Apply a boolean environment variable using a direct setter.
	 */
	public static void applyEnvBoolean(String envName, Consumer<Boolean> setter) {
		String value = envLookup.apply(envName);
		if (value != null) {
			log.info("Setting env {" + envName + "=" + value + "}");
			setter.accept(Boolean.parseBoolean(value));
		}
	}

	/**
	 * Helper to check whether the given text is empty or null.
	 * 
	 * @param text
	 * @return
	 */
	public static boolean isEmpty(String text) {
		return text == null || text.length() == 0;
	}
}
