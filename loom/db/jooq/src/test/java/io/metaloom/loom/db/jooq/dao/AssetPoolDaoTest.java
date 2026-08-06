package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.pool.AssetPool;
import io.metaloom.loom.db.model.pool.AssetPoolDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class AssetPoolDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<AssetPoolDao, AssetPool> {

	/**
	 * Build - but do not store - a pool. The name is the unique field (V2.20), so it has to vary with {@code i}.
	 *
	 * <p>
	 * A pool is either filesystem-backed or S3-backed, never both and never neither - the V2.20 CHECK constraint enforces {@code fs_path} XOR
	 * {@code s3_bucket}. Even indices build the filesystem shape, odd ones the S3 shape, so the 1024-element page run exercises both. Index 0 is the
	 * filesystem shape, which is what {@link #assertCreate(AssetPool)} sees; the S3 shape gets its own round trip in {@link #testS3Pool()}.
	 * </p>
	 */
	@Override
	public AssetPool createElement(User user, int i) {
		AssetPool pool = assetPoolDao().createAssetPool(user.getUuid(), "pool_" + i);
		if (i % 2 == 0) {
			pool.setFsPath("/var/lib/metaloom/pool_" + i);
		} else {
			pool.setS3Bucket("bucket-" + i);
			pool.setS3Region("eu-central-1");
			pool.setS3Endpoint("https://s3.example.org");
		}
		// Bytes, added in V2.24.
		pool.setFreeSpace(1024L * (i + 1));
		pool.setUsedSpace(512L * (i + 1));
		pool.setMeta(new JsonObject().put("key", "value"));
		return pool;
	}

	@Override
	public void assertCreate(AssetPool createdElement) {
		assertEquals("pool_0", createdElement.getName());
		assertEquals("/var/lib/metaloom/pool_0", createdElement.getFsPath());
		assertNull(createdElement.getS3Bucket(), "A filesystem pool must not carry S3 settings");
		assertNull(createdElement.getS3Region());
		assertNull(createdElement.getS3Endpoint());
		assertEquals(1024L, createdElement.getFreeSpace());
		assertEquals(512L, createdElement.getUsedSpace());
		assertNotNull(createdElement.getMeta());
		assertEquals("value", createdElement.getMeta().getString("key"));
	}

	/**
	 * The other half of the V2.20 discriminator: an S3 pool carries bucket, region and endpoint and leaves {@code fs_path} empty.
	 */
	@Test
	public void testS3Pool() {
		AssetPool pool = createElement(dummyUser(), 1);
		assetPoolDao().store(pool);

		AssetPool loaded = assetPoolDao().load(pool.getUuid());
		assertNotNull(loaded);
		assertEquals("bucket-1", loaded.getS3Bucket());
		assertEquals("eu-central-1", loaded.getS3Region());
		assertEquals("https://s3.example.org", loaded.getS3Endpoint());
		assertNull(loaded.getFsPath(), "An S3 pool must not carry a filesystem path");
		assertEquals(2048L, loaded.getFreeSpace());
		assertEquals(1024L, loaded.getUsedSpace());
	}

	/**
	 * A library pointing at a pool blocks the pool delete - V2.63 declares {@code library.pool_uuid} {@code ON DELETE RESTRICT} so a deleted bucket
	 * row cannot silently re-point the library at the local upload directory. The delete goes through once the library lets go.
	 */
	@Test
	public void testLibraryReferenceBlocksPoolDelete() {
		User user = dummyUser();

		AssetPool pool = assetPoolDao().createAssetPool(user.getUuid(), "restricted_pool");
		pool.setFsPath("/var/lib/metaloom/restricted");
		assetPoolDao().store(pool);

		Library library = libraryDao().createLibrary(user, "restricted_library");
		library.setPoolUuid(pool.getUuid());
		libraryDao().store(library);
		assertEquals(pool.getUuid(), libraryDao().load(library.getUuid()).getPoolUuid());

		assertThrows(DataAccessException.class, () -> assetPoolDao().delete(pool.getUuid()),
			"A library referencing the pool must block the delete");
		assertNotNull(assetPoolDao().load(pool.getUuid()), "The pool must survive the rejected delete");

		library.setPoolUuid(null);
		libraryDao().update(library);

		assetPoolDao().delete(pool.getUuid());
		assertNull(assetPoolDao().load(pool.getUuid()));
		assertNotNull(libraryDao().load(library.getUuid()), "The library must not be touched by the pool delete");
	}

	@Override
	public AssetPoolDao getDao() {
		return assetPoolDao();
	}

	@Override
	public void updateElement(AssetPool element) {
		element.setFreeSpace(4096L);
		element.setUsedSpace(2048L);
		element.setMeta(new JsonObject().put("updated", true));
	}

	@Override
	public void assertUpdate(AssetPool updatedElement) {
		assertEquals(4096L, updatedElement.getFreeSpace());
		assertEquals(2048L, updatedElement.getUsedSpace());
		assertNotNull(updatedElement.getMeta());
		assertEquals(true, updatedElement.getMeta().getBoolean("updated"));
	}

}
