package io.metaloom.cortex.node.relocate;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import dagger.MapKey;

/**
 * Map key binding an {@link AssignDestination} to the {@link AssignTarget} it serves.
 */
@MapKey
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface AssignTargetKey {

	AssignTarget value();
}
