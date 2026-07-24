package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.blacklist.Blacklist;
import io.metaloom.loom.db.model.blacklist.BlacklistDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;

public class BlacklistDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<BlacklistDao, Blacklist> {

	@Override
	public BlacklistDao getDao() {
		return blacklistDao();
	}

	@Override
	public Blacklist createElement(User user, int i) {
		// blacklist is unique per (asset, creator) - one verdict per user per asset - so each
		// element needs its own asset rather than reusing the fixture one.
		return getDao().createBlacklist(user, blacklistedAsset(user, i), "name_" + i);
	}

	/**
	 * Store an asset dedicated to the i-th blacklist entry. The SHA-512 is derived from the index so it stays unique and reproducible.
	 */
	private Asset blacklistedAsset(User user, int i) {
		String sha512Hex = String.format("%08x", i).repeat(16);
		Asset asset = assetDao().createAsset(user, SHA512.fromString(sha512Hex), "image/jpeg", "blacklisted_" + i + ".jpg",
			"/blacklisted/" + i + ".jpg", 42L);
		assetDao().store(asset);
		return asset;
	}

	@Override
	public void assertCreate(Blacklist createdElement) {
		assertEquals("name_0", createdElement.getName());
	}

	@Override
	public void assertUpdate(Blacklist updatedElement) {
		assertEquals("new_name", updatedElement.getName());
	}

	@Override
	public void updateElement(Blacklist element) {
		element.setName("new_name");
	}

}
