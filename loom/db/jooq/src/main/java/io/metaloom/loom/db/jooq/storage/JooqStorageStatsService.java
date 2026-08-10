package io.metaloom.loom.db.jooq.storage;

import static io.metaloom.loom.db.jooq.tables.JooqAsset.ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqAssetLocation.ASSET_LOCATION;
import static io.metaloom.loom.db.jooq.tables.JooqAttachment.ATTACHMENT;
import static io.metaloom.loom.db.jooq.tables.JooqAttachmentBinary.ATTACHMENT_BINARY;
import static io.metaloom.loom.db.jooq.tables.JooqPerson.PERSON;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.Table;

import io.metaloom.loom.db.storage.StorageCategory;
import io.metaloom.loom.db.storage.StorageCategoryStat;
import io.metaloom.loom.db.storage.StorageReport;
import io.metaloom.loom.db.storage.StorageReport.StoragePoolStat;
import io.metaloom.loom.db.storage.StorageStatsService;

/**
 * The storage report, computed in Postgres.
 *
 * <p>
 * Two facts about the schema shape every query here, and getting either wrong produces a plausible-looking wrong number:
 * </p>
 *
 * <ul>
 * <li><strong>{@code attachment} carries neither {@code size} nor {@code pool_uuid}.</strong> Both live on {@code attachment_binary} (V2.13, V2.63),
 * so every aggregate joins it.</li>
 * <li><strong>{@code attachment_binary} is shared.</strong> It is keyed by {@code sha512sum}, so N attachment rows can resolve to one stored object -
 * which is the whole point of a content-addressed store, and the reason logical and distinct bytes are reported separately.</li>
 * </ul>
 *
 * <p>
 * Asset binaries are the same story in a different pair of tables: {@code asset_location} has no size column, so the size comes from {@code asset},
 * and one asset may hold a location per library it was imported into (V2.48).
 * </p>
 */
@Singleton
public class JooqStorageStatsService implements StorageStatsService {

	private static final String ROWS = "rows";

	private static final String DISTINCT_ROWS = "distinct_rows";

	private static final String CATEGORY = "category";

	private static final String SHA512SUM = "sha512sum";

	private static final String SIZE = "size";

	private static final String POOL_UUID = "pool_uuid";

	private static final String PATH = "path";

	private final DSLContext ctx;

	@Inject
	public JooqStorageStatsService(DSLContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public StorageReport report() {
		Map<StorageCategory, StorageCategoryStat> stats = new EnumMap<>(StorageCategory.class);
		attachmentLogical(stats);
		attachmentDistinct(stats);
		assetBinaries(stats);

		// Every category, always - including the ones at zero. A row that disappears when empty is
		// indistinguishable from a query that stopped working.
		List<StorageCategoryStat> categories = new ArrayList<>();
		for (StorageCategory category : StorageCategory.values()) {
			categories.add(stats.getOrDefault(category, StorageCategoryStat.empty(category)));
		}

		Record2<Integer, java.math.BigDecimal> total = ctx
			.select(count(), sum(ATTACHMENT_BINARY.SIZE))
			.from(ATTACHMENT_BINARY)
			.fetchOne();

		Record2<Integer, java.math.BigDecimal> orphans = ctx
			.select(count(), sum(ATTACHMENT_BINARY.SIZE))
			.from(ATTACHMENT_BINARY)
			.whereNotExists(ctx.selectOne().from(ATTACHMENT).where(ATTACHMENT.BINARY_SHA512SUM.eq(ATTACHMENT_BINARY.SHA512SUM)))
			.fetchOne();

		return new StorageReport(categories, perPool(),
			asLong(total, 0), asBytes(total),
			asLong(orphans, 0), asBytes(orphans));
	}

	/**
	 * One row per attachment, carrying the category it falls into and the object it resolves to.
	 *
	 * <p>
	 * Both attachment aggregates read from this rather than repeating the joins, and it exists as a derived table rather than as an inline expression
	 * for a concrete reason: Postgres will not accept a {@code CASE} carrying bind parameters in a {@code GROUP BY}, because it compares the grouping
	 * expression to the projected one syntactically and two identically-built {@code ?} placeholders are not the same node. Naming the column once and
	 * grouping by the name sidesteps that entirely.
	 * </p>
	 *
	 * <p>
	 * A left join onto {@code attachment_binary}, not an inner one: an attachment whose binary row is missing is a broken row, and dropping it here
	 * would silently shrink the element count that is supposed to expose it. Its size reads as null and contributes zero, which is the truth.
	 * </p>
	 */
	private Table<Record3<String, String, Long>> attachmentRows() {
		return ctx.select(
			categoryField().as(CATEGORY),
			ATTACHMENT_BINARY.SHA512SUM.as(SHA512SUM),
			ATTACHMENT_BINARY.SIZE.as(SIZE))
			.from(ATTACHMENT)
			.leftJoin(ATTACHMENT_BINARY).on(ATTACHMENT_BINARY.SHA512SUM.eq(ATTACHMENT.BINARY_SHA512SUM))
			.leftJoin(PERSON).on(PERSON.UUID.eq(ATTACHMENT.PERSON_UUID))
			.asTable(ROWS);
	}

	/** Rows and the bytes they claim, per category. */
	private void attachmentLogical(Map<StorageCategory, StorageCategoryStat> stats) {
		Field<String> category = field(name(ROWS, CATEGORY), String.class);
		Field<Long> size = field(name(ROWS, SIZE), Long.class);

		ctx.select(category, count(), coalesce(sum(size), java.math.BigDecimal.ZERO))
			.from(attachmentRows())
			.groupBy(category)
			.fetch()
			.forEach(record -> {
				StorageCategory parsed = parse(record.value1());
				if (parsed == null) {
					return;
				}
				stats.merge(parsed,
					new StorageCategoryStat(parsed, record.value2(), record.value3().longValue(), 0, 0),
					JooqStorageStatsService::mergeLogical);
			});
	}

	/**
	 * Distinct stored objects and their real size, per category.
	 *
	 * <p>
	 * {@code SELECT DISTINCT (category, sha512sum, size)} is what makes this the physical figure: two attachments of the same category sharing a hash
	 * collapse to one row. Rows with no binary are excluded - an object that does not exist occupies nothing, and letting the nulls through would
	 * count one phantom object per category.
	 * </p>
	 */
	private void attachmentDistinct(Map<StorageCategory, StorageCategoryStat> stats) {
		Field<String> category = field(name(ROWS, CATEGORY), String.class);
		Field<String> sha512sum = field(name(ROWS, SHA512SUM), String.class);
		Field<Long> size = field(name(ROWS, SIZE), Long.class);

		Table<Record3<String, String, Long>> distinct = ctx.selectDistinct(category, sha512sum, size)
			.from(attachmentRows())
			.where(sha512sum.isNotNull())
			.asTable(DISTINCT_ROWS);

		Field<String> distinctCategory = field(name(DISTINCT_ROWS, CATEGORY), String.class);
		Field<Long> distinctSize = field(name(DISTINCT_ROWS, SIZE), Long.class);

		ctx.select(distinctCategory, count(), coalesce(sum(distinctSize), java.math.BigDecimal.ZERO))
			.from(distinct)
			.groupBy(distinctCategory)
			.fetch()
			.forEach(record -> {
				StorageCategory parsed = parse(record.value1());
				if (parsed == null) {
					return;
				}
				stats.merge(parsed,
					new StorageCategoryStat(parsed, 0, 0, record.value2(), record.value3().longValue()),
					JooqStorageStatsService::mergeDistinct);
			});
	}

	/**
	 * The original media, as a seventh category.
	 *
	 * <p>
	 * Distinct by {@code (pool_uuid, path)} rather than by hash, because that pair is what identifies one stored object here - the same content
	 * imported into two libraries within one pool deduplicates onto one file, and the same content in two pools genuinely occupies two.
	 * </p>
	 */
	private void assetBinaries(Map<StorageCategory, StorageCategoryStat> stats) {
		Record2<Integer, java.math.BigDecimal> logical = ctx
			.select(count(), coalesce(sum(ASSET.SIZE), java.math.BigDecimal.ZERO))
			.from(ASSET_LOCATION)
			.join(ASSET).on(ASSET.UUID.eq(ASSET_LOCATION.ASSET_UUID))
			.fetchOne();

		Field<Long> size = field(name(DISTINCT_ROWS, SIZE), Long.class);
		Record2<Integer, java.math.BigDecimal> physical = ctx
			.select(count(), coalesce(sum(size), java.math.BigDecimal.ZERO))
			.from(distinctAssetLocations())
			.fetchOne();

		stats.put(StorageCategory.ASSET_BINARY, new StorageCategoryStat(StorageCategory.ASSET_BINARY,
			asLong(logical, 0), asBytes(logical), asLong(physical, 0), asBytes(physical)));
	}

	/**
	 * Bytes per pool, attachments and asset binaries together.
	 *
	 * <p>
	 * Keyed by pool uuid with null for the deployment's default local storage, which is what {@code pool_uuid IS NULL} means everywhere else in the
	 * schema.
	 * </p>
	 */
	private List<StoragePoolStat> perPool() {
		Map<UUID, long[]> byPool = new java.util.HashMap<>();

		ctx.select(ATTACHMENT_BINARY.POOL_UUID, count(), coalesce(sum(ATTACHMENT_BINARY.SIZE), java.math.BigDecimal.ZERO))
			.from(ATTACHMENT_BINARY)
			.groupBy(ATTACHMENT_BINARY.POOL_UUID)
			.fetch()
			.forEach(record -> accumulate(byPool, record.value1(), record.value2(), record.value3().longValue()));

		Field<Long> size = field(name(DISTINCT_ROWS, SIZE), Long.class);
		Field<UUID> pool = field(name(DISTINCT_ROWS, POOL_UUID), UUID.class);
		ctx.select(pool, count(), coalesce(sum(size), java.math.BigDecimal.ZERO))
			.from(distinctAssetLocations())
			.groupBy(pool)
			.fetch()
			.forEach(record -> accumulate(byPool, record.value1(), record.value2(), record.value3().longValue()));

		List<StoragePoolStat> result = new ArrayList<>();
		byPool.forEach((poolUuid, counts) -> result.add(new StoragePoolStat(poolUuid, counts[0], counts[1])));
		return result;
	}

	/**
	 * One row per stored media object: distinct by {@code (pool_uuid, path)}.
	 *
	 * <p>
	 * That pair, rather than the content hash, is what identifies one object here. The same content imported into two libraries within one pool
	 * deduplicates onto one file and must count once; the same content in two pools genuinely occupies two.
	 * </p>
	 */
	private Table<Record3<UUID, String, Long>> distinctAssetLocations() {
		return ctx.selectDistinct(
			ASSET_LOCATION.POOL_UUID.as(POOL_UUID),
			ASSET_LOCATION.PATH.as(PATH),
			ASSET.SIZE.as(SIZE))
			.from(ASSET_LOCATION)
			.join(ASSET).on(ASSET.UUID.eq(ASSET_LOCATION.ASSET_UUID))
			.asTable(DISTINCT_ROWS);
	}

	private static void accumulate(Map<UUID, long[]> byPool, UUID poolUuid, long objects, long bytes) {
		long[] counts = byPool.computeIfAbsent(poolUuid, key -> new long[2]);
		counts[0] += objects;
		counts[1] += bytes;
	}

	/**
	 * The category expression: the attachment's type, except that a person image the person is actually shown by reports as an avatar.
	 */
	private static Field<String> categoryField() {
		return when(ATTACHMENT.TYPE.eq(io.metaloom.loom.db.jooq.enums.JooqAttachmentType.PERSON_IMAGE)
			.and(PERSON.AVATAR_ATTACHMENT_UUID.eq(ATTACHMENT.UUID)), StorageCategory.PERSON_AVATAR.name())
			.otherwise(ATTACHMENT.TYPE.cast(String.class));
	}

	/**
	 * A type this build does not know is dropped rather than failing the whole report.
	 *
	 * <p>
	 * The case it exists for is a rolling upgrade: a newer instance writes a new attachment type, an older one is asked for the report. Losing one
	 * row from a capacity dashboard is the right trade against answering 500.
	 * </p>
	 */
	private static StorageCategory parse(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return StorageCategory.valueOf(raw);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static StorageCategoryStat mergeLogical(StorageCategoryStat existing, StorageCategoryStat added) {
		return new StorageCategoryStat(existing.category(), added.elements(), added.logicalBytes(),
			existing.distinctObjects(), existing.distinctBytes());
	}

	private static StorageCategoryStat mergeDistinct(StorageCategoryStat existing, StorageCategoryStat added) {
		return new StorageCategoryStat(existing.category(), existing.elements(), existing.logicalBytes(),
			added.distinctObjects(), added.distinctBytes());
	}

	private static long asLong(Record2<Integer, java.math.BigDecimal> record, int fallback) {
		return record == null || record.value1() == null ? fallback : record.value1();
	}

	private static long asBytes(Record2<Integer, java.math.BigDecimal> record) {
		return record == null || record.value2() == null ? 0L : record.value2().longValue();
	}
}
