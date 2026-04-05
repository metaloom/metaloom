package io.metaloom.loom.api.options;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Marker interface for options
 */
public interface Option {

	Logger log = LoggerFactory.getLogger(Option.class);

	/**
	 * Override the annotated methods and fields of this option class and referenced sub options with environment variables.
	 */
	default void overrideWithEnv() {
		Class<?> cls = getClass();

		while (cls != null && cls != Object.class) {
			for (Method method : cls.getDeclaredMethods()) {
				if (method.getParameterCount() == 1 && method.isAnnotationPresent(EnvironmentVariable.class)) {
					OptionUtils.overrideWithEnvViaMethod(method, this);
				}
			}
			for (Field field : cls.getDeclaredFields()) {
				if (field.isAnnotationPresent(EnvironmentVariable.class)) {
					OptionUtils.overrideWitEnvViaFieldSet(field, this);
				}
				// check if the current fields is an option if so we call the override with env recursively
				if (Option.class.isAssignableFrom(field.getType())) {
					field.setAccessible(true);
					Option subOption;
					try {
						subOption = (Option) field.get(this);
					} catch (IllegalAccessException e) {
						throw new RuntimeException("Could not access sub option: " + field.getName(), e);
					}
					if (subOption != null) {
						log.trace("Override sub option: " + field.getName());
						subOption.overrideWithEnv();
					}
				}
			}

			cls = cls.getSuperclass();
		}
	}

}
