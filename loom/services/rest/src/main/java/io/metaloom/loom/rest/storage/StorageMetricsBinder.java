package io.metaloom.loom.rest.storage;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.db.storage.StorageCategory;
import io.metaloom.loom.db.storage.StorageCategoryStat;
import io.metaloom.loom.rest.service.impl.BinaryStorageResolver.BackendInfo;
import io.metaloom.loom.rest.service.impl.StorageCapacityGuard.Watermark;

/**
 * Publishes the {@code loom_storage_*} gauges.
 *
 * <p>
 * Takes suppliers rather than a {@code DaoCollection} or a resolver, for two reasons that both matter:
 * </p>
 *
 * <ul>
 * <li><strong>A gauge must never do the work.</strong> Micrometer polls a gauge's supplier on the Prometheus scrape thread. Reading free space there
 * means a {@code statvfs} per scrape, which on a stalled NFS mount hangs the scrape; running the aggregate SQL there means several table scans every
 * fifteen seconds. Both read a snapshot somebody else refreshed on a timer.</li>
 * <li><strong>It has to be constructible without a database.</strong> {@code MetricsCatalogScrapeTest} builds the real instrumentation sites with no
 * Postgres behind them and fails the build on any {@code METRICS.md} name that does not show up in a scrape. A binder that needed a live DAO could not
 * be exercised there, and the documented names would drift.</li>
 * </ul>
 *
 * <p>
 * Series are bound lazily, one per pool and one per category, as those first appear. Both label sets are bounded - pools are operator-created and
 * categories are a fixed enum - which is the cardinality rule {@code METRICS.md} states.
 * </p>
 */
public class StorageMetricsBinder {

	public static final String FREE_BYTES = "loom_storage_free_bytes";

	public static final String TOTAL_BYTES = "loom_storage_total_bytes";

	public static final String WATERMARK = "loom_storage_watermark";

	public static final String ATTACHMENT_BYTES = "loom_storage_attachment_bytes";

	public static final String ATTACHMENT_OBJECTS = "loom_storage_attachment_objects";

	private static final String POOL_TAG = "pool";

	private static final String CATEGORY_TAG = "category";

	private final LoomMetrics metrics;

	private final Supplier<List<BackendInfo>> backends;

	private final Supplier<List<StorageCategoryStat>> categories;

	private final Function<BackendInfo, Watermark> watermarkOf;

	private final Set<String> boundPools = ConcurrentHashMap.newKeySet();

	private final Set<StorageCategory> boundCategories = ConcurrentHashMap.newKeySet();

	/**
	 * @param metrics     the meter registry facade
	 * @param backends    the current backends; polled by this class, not by the gauges
	 * @param categories  the current per-category figures
	 * @param watermarkOf how to grade one backend's remaining capacity, normally {@code StorageCapacityGuard::evaluate} applied to its free bytes
	 */
	public StorageMetricsBinder(LoomMetrics metrics, Supplier<List<BackendInfo>> backends,
		Supplier<List<StorageCategoryStat>> categories, Function<BackendInfo, Watermark> watermarkOf) {
		this.metrics = metrics;
		this.backends = backends;
		this.categories = categories;
		this.watermarkOf = watermarkOf;
	}

	/**
	 * Bind a series for anything newly seen.
	 *
	 * <p>
	 * Call after each refresh. {@code bindGauge} is idempotent per {@code (name, tag)}, and the local sets exist only to avoid rebuilding the closures
	 * on every pass.
	 * </p>
	 */
	public void bind() {
		for (BackendInfo backend : backends.get()) {
			String pool = label(backend);
			if (!boundPools.add(pool)) {
				continue;
			}
			metrics.bindGauge(FREE_BYTES, POOL_TAG, pool, () -> value(pool, BackendInfo::freeBytes));
			metrics.bindGauge(TOTAL_BYTES, POOL_TAG, pool, () -> value(pool, BackendInfo::totalBytes));
			metrics.bindGauge(WATERMARK, POOL_TAG, pool, () -> watermark(pool));
		}
		for (StorageCategoryStat stat : categories.get()) {
			StorageCategory category = stat.category();
			if (!boundCategories.add(category)) {
				continue;
			}
			metrics.bindGauge(ATTACHMENT_BYTES, CATEGORY_TAG, category.name(), () -> categoryValue(category, StorageCategoryStat::distinctBytes));
			metrics.bindGauge(ATTACHMENT_OBJECTS, CATEGORY_TAG, category.name(),
				() -> categoryValue(category, StorageCategoryStat::distinctObjects));
		}
	}

	/**
	 * A backend the report no longer lists, or one that cannot say, reads as {@code NaN} rather than as zero.
	 *
	 * <p>
	 * Zero would mean "this bucket has no free space left", which is exactly the wrong thing for a threshold alert to see. Micrometer renders NaN as
	 * an absent sample, which is what "cannot tell" should look like on a graph.
	 * </p>
	 */
	private Number value(String pool, Function<BackendInfo, Long> reader) {
		return backends.get().stream()
			.filter(backend -> label(backend).equals(pool))
			.map(reader)
			.filter(Objects::nonNull)
			.findFirst()
			.map(Number.class::cast)
			.orElse(Double.NaN);
	}

	/**
	 * The watermark severity, or {@link Watermark#UNKNOWN}'s {@code -1} for a backend that has gone away.
	 *
	 * <p>
	 * A severity rather than an enum ordinal, so {@code max(loom_storage_watermark)} across pools reads as "how bad is the worst one" - the encoding
	 * {@code loom_node_circuit_breaker_state} already establishes.
	 * </p>
	 */
	private Number watermark(String pool) {
		return backends.get().stream()
			.filter(backend -> label(backend).equals(pool))
			.findFirst()
			.map(backend -> (Number) watermarkOf.apply(backend).severity())
			.orElse(Watermark.UNKNOWN.severity());
	}

	private Number categoryValue(StorageCategory category, ToLongFunction<StorageCategoryStat> reader) {
		return categories.get().stream()
			.filter(stat -> stat.category() == category)
			.mapToLong(reader)
			.findFirst()
			.orElse(0L);
	}

	/**
	 * The pool's name, falling back to its uuid.
	 *
	 * <p>
	 * A name rather than a uuid because a capacity dashboard is read by a human, and {@code pool="Archive S3"} is the label that makes an alert
	 * actionable. Two pools sharing a name would share a series; the report itself shows both name and uuid, which is where that ambiguity gets
	 * resolved.
	 * </p>
	 */
	private static String label(BackendInfo backend) {
		if (backend.poolName() != null && !backend.poolName().isBlank()) {
			return backend.poolName();
		}
		return backend.poolUuid() == null ? "default" : backend.poolUuid().toString();
	}
}
