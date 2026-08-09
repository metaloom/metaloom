package io.metaloom.cortex.node.relocate;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import dagger.MapKey;

/**
 * Map key binding a {@link MoveDestination} to the {@link MoveTarget} it serves.
 *
 * <p>
 * An enum key rather than a {@code @StringKey}, for the same reason {@code FilterByKey} uses one: a strategy bound to a target that does not exist
 * should be a compile error, not a node that discovers at runtime it has nowhere to put the bytes.
 * </p>
 */
@MapKey
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface MoveTargetKey {

	MoveTarget value();
}
