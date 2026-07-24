package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

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
		// The natural key is (library_uuid, path), so each location needs its own path.
		return assetLocationDao().createAssetLocation(DUMMY_IMAGE_ORIGIN + "_" + i, ASSET_UUID, ADMIN_UUID, LIBRARY_UUID);
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

	@Test
	public void testFindForAsset() {
		AssetLocationDao dao = assetLocationDao();

		// An asset without any stored location must yield an empty result.
		assertTrue(dao.findForAsset(UUID.randomUUID()).isEmpty(), "A random asset must not have any locations");

		AtomicReference<UUID> ref = new AtomicReference<>();
		transaction(t -> {
			AssetLocation location = dao.createAssetLocation("/pool/movie.mp4", ASSET_UUID, ADMIN_UUID, LIBRARY_UUID);
			location.setMimeType("video/mp4");
			location.setState("PRESENT");
			location.setLicense("CC-BY-4.0");
			location.setLockedByUuid(ADMIN_UUID);
			dao.store(location);
			ref.set(location.getUuid());
		});

		// Several locations may share one asset - the same content can live at more than one
		// path. The fixture already stores one, so the new one is additional.
		List<AssetLocation> found = dao.findForAsset(ASSET_UUID);
		assertTrue(found.size() >= 2, "The asset should carry the fixture location plus the one stored here");

		AssetLocation loaded = found.stream()
			.filter(l -> ref.get().equals(l.getUuid()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("The stored location was not returned for the asset"));
		assertEquals(ref.get(), loaded.getUuid());
		assertEquals(ASSET_UUID, loaded.getAssetUuid());
		assertEquals("/pool/movie.mp4", loaded.getPath());
		assertEquals("video/mp4", loaded.getMimeType());
		assertEquals("PRESENT", loaded.getState());
		assertEquals("CC-BY-4.0", loaded.getLicense());
		assertEquals(ADMIN_UUID, loaded.getLockedByUuid());
	}

}