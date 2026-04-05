package io.metaloom.loom.db;

import io.metaloom.loom.db.dagger.DaoProvider;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.project.Project;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.transaction.TransactionCallable;
import io.metaloom.loom.test.data.TestValues;

/**
 * Defines methods that can be used to fetch elements from the database testfixture (e.g. user)
 */
public interface FixtureElementProvider extends DaoProvider, TestValues {

	void transaction(TransactionCallable callable);

	default User dummyUser() {
		return userDao().load(USER_UUID);
	}

	default User adminUser() {
		return userDao().load(ADMIN_UUID);
	}

	default Project project() {
		return projectDao().load(PROJECT_UUID);
	}

	default Library library() {
		return libraryDao().load(LIBRARY_UUID);
	}

	default Asset asset() {
		return assetDao().load(ASSET_UUID);
	}
}
