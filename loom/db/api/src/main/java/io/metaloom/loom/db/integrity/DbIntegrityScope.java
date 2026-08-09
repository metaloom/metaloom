package io.metaloom.loom.db.integrity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which checks to run, and how much detail to keep.
 *
 * <p>
 * An empty {@code codes} or {@code categories} set means "no filter on that axis", not "nothing".
 * The filters are ANDed: a check runs when it passes every filter that is actually set.
 * </p>
 *
 * @param codes
 *            run only these check codes, or empty for all
 * @param categories
 *            run only checks in these categories, or empty for all
 * @param excluded
 *            never run these codes. Applied after {@code codes}, so an explicit exclusion always
 *            wins - this is what a test class's ignore list becomes
 * @param minSeverity
 *            run only checks declared at this severity or worse
 * @param sampleLimit
 *            how many offending rows to name per finding
 */
public record DbIntegrityScope(
	Set<String> codes,
	Set<DbIntegrityCategory> categories,
	Set<String> excluded,
	DbIntegritySeverity minSeverity,
	int sampleLimit) {

	/** Rows named per finding when the caller does not say. */
	public static final int DEFAULT_SAMPLE_LIMIT = 20;

	/** Hard ceiling on {@code sampleLimit}, so a query parameter cannot ask for the whole table. */
	public static final int MAX_SAMPLE_LIMIT = 100;

	public DbIntegrityScope {
		codes = codes == null ? Set.of() : Set.copyOf(codes);
		categories = categories == null ? Set.of() : Set.copyOf(categories);
		excluded = excluded == null ? Set.of() : Set.copyOf(excluded);
		minSeverity = minSeverity == null ? DbIntegritySeverity.INFO : minSeverity;
		sampleLimit = Math.clamp(sampleLimit, 0, MAX_SAMPLE_LIMIT);
	}

	/** Every check, every category, default sample size. */
	public static DbIntegrityScope all() {
		return new DbIntegrityScope(Set.of(), Set.of(), Set.of(), DbIntegritySeverity.INFO, DEFAULT_SAMPLE_LIMIT);
	}

	/**
	 * Only the checks declared {@link DbIntegritySeverity#ERROR}. The cheap scope: it is what a test
	 * falls back to if the full sweep ever costs measurable time.
	 */
	public static DbIntegrityScope errorsOnly() {
		return all().withMinSeverity(DbIntegritySeverity.ERROR);
	}

	/** Just these codes, whatever their severity. */
	public static DbIntegrityScope of(String... codes) {
		return all().withCodes(Set.of(codes));
	}

	/** Just these categories. */
	public static DbIntegrityScope ofCategories(DbIntegrityCategory... categories) {
		return all().withCategories(Set.of(categories));
	}

	public DbIntegrityScope withCodes(Set<String> codes) {
		return new DbIntegrityScope(codes, categories, excluded, minSeverity, sampleLimit);
	}

	public DbIntegrityScope withCategories(Set<DbIntegrityCategory> categories) {
		return new DbIntegrityScope(codes, categories, excluded, minSeverity, sampleLimit);
	}

	public DbIntegrityScope withMinSeverity(DbIntegritySeverity minSeverity) {
		return new DbIntegrityScope(codes, categories, excluded, minSeverity, sampleLimit);
	}

	public DbIntegrityScope withSampleLimit(int sampleLimit) {
		return new DbIntegrityScope(codes, categories, excluded, minSeverity, sampleLimit);
	}

	/** Add codes to the exclusion set, keeping any already there. */
	public DbIntegrityScope excluding(Set<String> more) {
		if (more == null || more.isEmpty()) {
			return this;
		}
		Set<String> merged = new LinkedHashSet<>(excluded);
		merged.addAll(more);
		return new DbIntegrityScope(codes, categories, merged, minSeverity, sampleLimit);
	}

	public DbIntegrityScope excluding(String... more) {
		return excluding(new LinkedHashSet<>(Arrays.asList(more)));
	}

	/** Whether the described check should run under this scope. */
	public boolean accepts(DbIntegrityCheckInfo info) {
		if (excluded.contains(info.code())) {
			return false;
		}
		if (!codes.isEmpty() && !codes.contains(info.code())) {
			return false;
		}
		if (!categories.isEmpty() && !categories.contains(info.category())) {
			return false;
		}
		return info.severity().atLeast(minSeverity);
	}
}
