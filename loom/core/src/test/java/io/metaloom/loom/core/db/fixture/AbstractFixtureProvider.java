package io.metaloom.loom.core.db.fixture;

import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.core.dagger.LoomCoreComponent;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.dagger.DaoProvider;
import io.metaloom.loom.test.data.TestValues;

public abstract class AbstractFixtureProvider implements TestValues, DaoProvider {

	protected final AuthenticationService authService;

	// Other
	protected final DaoCollection daos;

	public AbstractFixtureProvider(LoomCoreComponent component) {
		this.daos = component.daos();
		this.authService = component.authService();
	}

	@Override
	public DaoCollection daos() {
		return daos;
	}
}
