package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.reaction.Reaction;
import io.metaloom.loom.db.model.reaction.ReactionDao;
import io.metaloom.loom.db.model.user.User;

/**
 * The DAO layer holds {@code reaction.type} as a plain {@link String} on purpose — the enum boundary is REST, not here — so these cases deliberately
 * store types that are not {@link ReactionType} constants.
 */
public class ReactionDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<ReactionDao, Reaction> {

	@Override
	public ReactionDao getDao() {
		return reactionDao();
	}

	@Override
	public Reaction createElement(User user, int i) {
		return getDao().createReaction(user, "type_" + i);
	}

	@Override
	public void assertCreate(Reaction createdElement) {
		assertEquals("type_0", createdElement.getType());
	}

	@Override
	public void assertUpdate(Reaction updatedElement) {
		assertEquals("new", updatedElement.getType());
	}

	@Override
	public void updateElement(Reaction reaction) {
		reaction.setType("new");
	}

	/**
	 * A rating and an emoji reaction by one user on one asset are two rows.
	 *
	 * <p>
	 * {@code UNIQUE (creator_uuid, type, asset_uuid)} is what makes the rating one-per-user-per-asset, and before {@code RATING} existed the rating
	 * borrowed {@code SATISFIED} — so writing a rating silently overwrote that user's reaction and the other way round. Storing both here is the
	 * assertion that they no longer collide.
	 * </p>
	 */
	@Test
	public void testRatingAndReactionCoexist() {
		User user = adminUser();
		Asset asset = asset();

		Reaction reaction = getDao().createReaction(user, ReactionType.SATISFIED.name());
		reaction.setAssetUuid(asset.getUuid());
		getDao().store(reaction);

		Reaction rating = getDao().createReaction(user, ReactionType.RATING.name());
		rating.setAssetUuid(asset.getUuid());
		rating.setRating(7);
		getDao().store(rating);

		Reaction loaded = getDao().load(rating.getUuid());
		assertNotNull(loaded, "The rating must be readable");
		assertEquals(ReactionType.RATING.name(), loaded.getType());
		assertEquals(7, loaded.getRating());
		assertNotNull(getDao().load(reaction.getUuid()), "The emoji reaction must survive the rating");
	}

}
