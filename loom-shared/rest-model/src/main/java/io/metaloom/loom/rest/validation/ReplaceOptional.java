package io.metaloom.loom.rest.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request model property as not required for a full replace (PUT) request.
 *
 * By default {@link ReplaceValidator} demands that a PUT body carries every JSON property of the request model. Properties which are only meaningful for
 * a subset of the resources (e.g. the video specific information of an asset) can opt out via this annotation.
 *
 * PATCH requests are unaffected - every property is optional there.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD })
public @interface ReplaceOptional {

}
