package io.metaloom.cortex.node.tag;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import dagger.MapKey;

/**
 * Map key binding a {@link TagStrategy} to the {@link TagBy} value it serves.
 *
 * <p>
 * An enum key rather than a {@code @StringKey} so a strategy bound to a value that does not exist is
 * a compile error rather than a node that silently tags nothing for a whole run.
 * </p>
 */
@MapKey
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface TagByKey {

	TagBy value();
}
