package io.metaloom.cortex.node.filter;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import dagger.MapKey;

/**
 * Map key binding a {@link FilterStrategy} to the {@link FilterBy} value it serves.
 *
 * <p>
 * An enum key rather than a {@code @StringKey} so a strategy bound to a value that does not exist is
 * a compile error rather than a filter that silently answers {@code other} for a whole run.
 * </p>
 */
@MapKey
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface FilterByKey {

	FilterBy value();
}
