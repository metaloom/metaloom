package io.metaloom.loom.db.jooq.integrity.check;

import java.util.List;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSweepCheck;

/**
 * A name column that is present, NOT NULL, and empty.
 *
 * <p>
 * {@code NOT NULL} stops the null and says nothing about the empty string. Every column swept here
 * is one the UI renders as the thing's identity, so a blank value produces a row a user can see and
 * cannot refer to - and, for the ones inside a unique key, one that blocks the next blank from being
 * created for an unrelated-looking reason.
 * </p>
 *
 * <p>
 * Only NOT NULL columns are listed. A nullable name means "not named yet" and is a different
 * question, asked - where it is worth asking at all - by {@link MissingNameCheck}.
 * </p>
 */
public final class BlankNameCheck extends AbstractSweepCheck {

	public BlankNameCheck() {
		super(new DbIntegrityCheckInfo(
			DbIntegrityCodes.BLANK_NAME,
			"Empty name",
			DbIntegrityCategory.MANDATORY_FIELD,
			DbIntegritySeverity.ERROR,
			"(every named entity)", "name",
			"A required name is present but empty. NOT NULL does not stop the empty string, and the"
				+ " UI renders these as the entity's identity."),
			branches());
	}

	private static List<String> branches() {
		return List.of(
			blank("user", "username"),
			blank("role", "name"),
			blank("group", "name"),
			blank("tag", "name"),
			blank("project", "name"),
			blank("library", "name"),
			blank("collection", "name"),
			blank("asset_pool", "name"),
			blank("vector_config", "name"),
			blank("skill", "name"),
			// Pipelines are named on the version, not the pipeline (V2.30).
			blank("pipeline_version", "name"),
			blank("asset", "filename"),
			blank("attachment", "filename"),
			blank("task", "title"));
	}

	private static String blank(String table, String column) {
		return branch(table, "t.\"uuid\"",
			"'" + column + " is blank'",
			"trim(t.\"" + column + "\") = ''");
	}
}
