package io.metaloom.loom.db.jooq.integrity.check;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractConditionCheck;
import io.metaloom.loom.db.jooq.tables.JooqUser;

/**
 * A soft-deleted user still carrying the personal data the deletion was supposed to remove.
 *
 * <p>
 * {@code User.markDeleted()} sets {@code deleted} and clears {@code firstname}, {@code lastname} and
 * {@code meta} - the row survives only so the hundred-odd {@code creator_uuid} references stay
 * valid, not so the person's details do. A row where {@code deleted} is set but the fields are still
 * populated was soft-deleted by something that did not go through that method: a migration, a
 * fixture, or hand-written SQL.
 * </p>
 *
 * <p>
 * WARN rather than ERROR because nothing breaks. What is at stake is a data-protection promise, not
 * a query, and the remedy is a human deciding whether that row should be scrubbed.
 * </p>
 *
 * <p>
 * {@code username} is deliberately not part of the condition even though {@code markDeleted()}
 * clears it too: the column is {@code varchar UNIQUE NOT NULL}, so a row that kept its username
 * cannot be distinguished here from one that never had it cleared, and a row that lost it could not
 * have been written at all.
 * </p>
 */
public final class SoftDeletedUserNotAnonymisedCheck extends AbstractConditionCheck {

	public SoftDeletedUserNotAnonymisedCheck() {
		super(new DbIntegrityCheckInfo(
			DbIntegrityCodes.SOFT_DELETED_USER_NOT_ANONYMISED,
			DbIntegrityCategory.MANDATORY_FIELD,
			DbIntegritySeverity.WARN,
			"user", "firstname, lastname, meta",
			"A soft-deleted user still holds the personal data markDeleted() clears, so the row was"
				+ " deleted by something other than the application."));
	}

	@Override
	protected Table<?> table() {
		return JooqUser.USER;
	}

	@Override
	protected Condition condition() {
		return JooqUser.USER.DELETED.isTrue()
			.and(JooqUser.USER.FIRSTNAME.isNotNull()
				.or(JooqUser.USER.LASTNAME.isNotNull())
				.or(JooqUser.USER.META.isNotNull()));
	}

	@Override
	protected Field<?>[] detailFields() {
		return new Field<?>[] { JooqUser.USER.FIRSTNAME, JooqUser.USER.LASTNAME };
	}
}
