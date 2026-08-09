package io.metaloom.loom.db.jooq.integrity.check;

import java.util.List;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSweepCheck;

/**
 * A soft-deleted user that is still wired into live work.
 *
 * <p>
 * {@code user.deleted} is the only soft delete in the schema, and it exists so that the roughly one
 * hundred plain {@code creator_uuid} references survive - "a user is not deleted out from under
 * their work", as several migrations put it. That part is deliberate and is not checked here.
 * </p>
 *
 * <p>
 * What is <em>not</em> deliberate is that soft delete bypasses every {@code ON DELETE CASCADE}
 * pointed at {@code "user"}, because no row is ever deleted. The rows below would have been removed
 * by a hard delete and are not:
 * </p>
 * <ul>
 * <li>{@code token} - the deleted user's API keys still authenticate. This is the one that matters.</li>
 * <li>{@code task_assignee} - tasks stay assigned to someone who is gone, and "assigned to me"
 * queries keep returning them.</li>
 * <li>{@code notification} - the inbox keeps filling for a recipient who cannot read it.</li>
 * <li>{@code user_group}, {@code user_permission} - the account keeps its memberships and grants.</li>
 * </ul>
 *
 * <p>
 * ERROR because the token case is a live authentication path, not a tidiness question.
 * </p>
 */
public final class SoftDeletedUserHasLiveWorkCheck extends AbstractSweepCheck {

	public SoftDeletedUserHasLiveWorkCheck() {
		super(new DbIntegrityCheckInfo(
			DbIntegrityCodes.SOFT_DELETED_USER_HAS_LIVE_WORK,
			DbIntegrityCategory.DANGLING,
			DbIntegritySeverity.ERROR,
			"token, task_assignee, notification, user_group, user_permission", "user_uuid",
			"A soft-deleted user still holds live work. Soft delete never removes the row, so every"
				+ " ON DELETE CASCADE pointed at \"user\" is bypassed - most seriously the one on"
				+ " token, which means the account's API keys still authenticate."),
			branches());
	}

	private static List<String> branches() {
		return List.of(
			deletedUser("token", "uuid", "'token ' || coalesce(t.\"name\", '(unnamed)')", "creator_uuid"),
			deletedUser("task_assignee", null, "'task ' || t.\"task_uuid\"", "user_uuid"),
			deletedUser("notification", "uuid", "'notification ' || t.\"type\"", "recipient_uuid"),
			deletedUser("user_group", null, "'group ' || t.\"group_uuid\"", "user_uuid"),
			deletedUser("user_permission", null, "'permission ' || t.\"permission\"", "user_uuid"));
	}

	/**
	 * @param table
	 *            the table holding the reference
	 * @param idColumn
	 *            its uuid column, or null for the tables without one
	 * @param detail
	 *            what to show so the row can be found again
	 * @param userColumn
	 *            the column naming the user
	 */
	private static String deletedUser(String table, String idColumn, String detail, String userColumn) {
		return branch(table,
			idColumn == null ? null : "t.\"" + idColumn + "\"",
			detail,
			"t.\"" + userColumn + "\" is not null and exists (select 1 from \"user\" u"
				+ " where u.\"uuid\" = t.\"" + userColumn + "\" and u.\"deleted\")");
	}
}
