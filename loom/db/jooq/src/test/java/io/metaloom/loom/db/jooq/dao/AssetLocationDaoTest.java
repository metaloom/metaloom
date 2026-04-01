package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.AssetLocation;
import io.metaloom.loom.db.model.asset.AssetLocationDao;
import io.metaloom.loom.db.model.user.User;

public class AssetLocationDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<AssetLocationDao, AssetLocation> {
	@Override
	public AssetLocationDao getDao() {
		return assetLocationDao();
	}

	@Override
	public AssetLocation createElement(User user, int i) {
		return assetLocationDao().createAssetLocation(DUMMY_IMAGE_ORIGIN, ASSET_UUID, ADMIN_UUID, LIBRARY_UUID);
	}
	@Override
	public void assertCreate(AssetLocation createdElement) {
		assertEquals(LIBRARY_UUID, createdElement.getLibraryUuid());
		assertEquals(ASSET_UUID, createdElement.getAssetUuid());
	}

	@Override
	public void updateElement(AssetLocation element) {
		element.setPath("new path");
	}

	@Override
	public void assertUpdate(AssetLocation updatedAsset) {
		assertEquals("new path", updatedAsset.getPath());
	}

}