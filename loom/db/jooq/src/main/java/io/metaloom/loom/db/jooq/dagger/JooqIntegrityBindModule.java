package io.metaloom.loom.db.jooq.dagger;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import io.metaloom.loom.db.integrity.DbIntegrityService;
import io.metaloom.loom.db.jooq.integrity.JooqDbIntegrityService;

/**
 * Binds the database integrity checks to their jOOQ implementation.
 *
 * <p>
 * Its own module, and abstract, because {@link JooqModule} is a concrete {@code @Provides} class and
 * {@code @Binds} cannot live in one.
 * </p>
 *
 * <p>
 * Installed in both {@code LoomCoreComponent} and the jOOQ {@code TestComponent}, which is the whole
 * point of the subsystem: the same checks answer the admin screen and a DAO test.
 * </p>
 */
@Module
public abstract class JooqIntegrityBindModule {

	@Binds
	@Singleton
	abstract DbIntegrityService dbIntegrityService(JooqDbIntegrityService impl);
}
