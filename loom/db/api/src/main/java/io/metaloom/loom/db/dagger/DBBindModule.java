package io.metaloom.loom.db.dagger;

import dagger.Binds;
import dagger.Module;

@Module
public abstract class DBBindModule {

	@Binds
	abstract DaoCollection daoCollection(DaoCollectionImpl collection);
}
