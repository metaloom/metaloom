package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.user.User;

public class AssetDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<AssetDao, Asset> {
	@Override
	public AssetDao getDao() {
		return assetDao();
	}

	@Override
	public Asset createElement(User user, int i) {
		return assetDao().createAsset(user.getUuid(), SHA512SUM, IMAGE_MIMETYPE, DUMMY_IMAGE_FILENAME, DUMMY_IMAGE_ORIGIN, 42L);
	}

	@Override
	public void assertCreate(Asset createdElement) {
		assertEquals(SHA512SUM, createdElement.getSHA512());
		assertEquals(IMAGE_MIMETYPE, createdElement.getMimeType());
		assertEquals(DUMMY_IMAGE_FILENAME, createdElement.getFilename());
		assertEquals(DUMMY_IMAGE_ORIGIN, createdElement.getInitialOrigin());
		assertEquals(42L, createdElement.getSize());
	}

	@Override
	public void updateElement(Asset element) {
		element.setMimeType("new mimetype");
	}

	@Override
	public void assertUpdate(Asset updatedAsset) {
		assertEquals("new mimetype", updatedAsset.getMimeType());
	}

}
