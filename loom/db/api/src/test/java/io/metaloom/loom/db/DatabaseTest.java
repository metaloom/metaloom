package io.metaloom.loom.db;

import io.metaloom.loom.db.dagger.DaoProvider;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetLocation;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.test.data.TestValues;

public interface DatabaseTest extends TestValues, DaoProvider {

	default User createUser(String username) {
		User user = userDao().createUser(username);
		userDao().store(user);
		return user;
	}

	default AssetLocation createAsset(String filename, User user) {
		Asset asset = createAsset(user);
		Library library = createLibrary(user, LIBRARY_NAME);
		return assetLocationDao().createAssetLocation(filename, asset.getUuid(), user.getUuid(), library.getUuid());
	}

	default Library createLibrary(User user, String name) {
		Library library = libraryDao().createLibrary(user, name);
		libraryDao().store(library);
		return library;
	}

	default Asset createAsset(User user) {
		Asset asset = assetDao().createAsset(user, SHA512SUM, IMAGE_MIMETYPE, DUMMY_IMAGE_FILENAME, DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

}
