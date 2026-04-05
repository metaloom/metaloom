package io.metaloom.loom.api.options;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to annotate fields which can be configured via environment variable.
 */
@Target({ ElementType.ANNOTATION_TYPE, ElementType.FIELD, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface EnvironmentVariable {

	/**
	 * Description of the variable.
	 * 
	 * @return
	 */
	String description();

	/**
	 * Name of the variable.
	 * 
	 * @return
	 */
	String name();

	/**
	 * Whether this variable is sensitive (should not be logged)
	 * 
	 * @return
	 */
	boolean isSensitive() default false;
}