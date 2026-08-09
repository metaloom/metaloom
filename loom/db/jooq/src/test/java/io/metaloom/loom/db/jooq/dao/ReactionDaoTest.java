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
 * The DAO layer holds {@code reaction.type} as a plain {@link String}, because the enum boundary is REST rather than persistence. That is not licence
 * to store arbitrary strings here: {@code ReactionModelBuilder.toResponse} reads the column back with {@link ReactionType#valueOf}, so a row whose
 * type is not a constant makes every REST read of that row a 500. The fixture once stored an asset's mime type in this column and did exactly that.
 *
 * <p>
 * So these cases store real {@link ReactionType} names. {@code INVALID_REACTION_TYPE} in the integrity checks is what noticed they did not, and is
 * what will notice again.
 * </p>
 */
public class ReactionDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<ReactionDao, Reaction> {

	@Override
	public ReactionDao getDao() {
		return reactionDao();
	}

	@Override
	public Reaction createElement(User user, int i) {
		// Cycled rather than fixed, so paging gets a spread. The UNIQUE indexes are on
		// (creator_uuid, type, <subject>_uuid) and every subject here is NULL, which Postgres counts
		// as distinct - so repeats across 1024 rows do not collide.
		ReactionType type = ReactionType.values()[i % ReactionType.values().length];
		return getDao().createReaction(user, type.name());
	}

	@Override
	public void assertCreate(Reaction createdElement) {
		assertEquals(ReactionType.values()[0].name(), createdElement.getType());
	}

	@Override
	public void assertUpdate(Reaction updatedElement) {
		assertEquals(ReactionType.THUMBSDOWN.name(), updatedElement.getType());
	}

	@Override
	public void updateElement(Reaction reaction) {
		reaction.setType(ReactionType.THUMBSDOWN.name());
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
