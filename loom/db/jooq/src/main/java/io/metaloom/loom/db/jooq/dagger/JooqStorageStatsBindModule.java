package io.metaloom.loom.db.jooq.dagger;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import io.metaloom.loom.db.jooq.storage.JooqStorageStatsService;
import io.metaloom.loom.db.storage.StorageStatsService;

/**
 * Binds the storage report to its jOOQ implementation.
 *
 * <p>
 * Its own module, and abstract, for the same reason {@link JooqIntegrityBindModule} is: {@link JooqModule} is a
 * concrete {@code @Provides} class and {@code @Binds} cannot live in one.
 * </p>
 */
@Module
public abstract class JooqStorageStatsBindModule {

	@Binds
	@Singleton
	abstract StorageStatsService storageStatsService(JooqStorageStatsService impl);
}
