package io.metaloom.loom.db.jooq.dao.cluster;

import static io.metaloom.loom.db.jooq.tables.JooqCluster.CLUSTER;
import static io.metaloom.loom.db.jooq.tables.JooqDetection.DETECTION;
import static io.metaloom.loom.db.jooq.tables.JooqEmbedding.EMBEDDING;
import static io.metaloom.loom.db.jooq.tables.JooqEmbeddingCluster.EMBEDDING_CLUSTER;
import static io.metaloom.loom.db.jooq.tables.JooqPerson.PERSON;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.impl.DSL;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.enums.JooqReviewStatus;
import io.metaloom.loom.db.jooq.tables.JooqCluster;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.cluster.ClusterDao;
import io.metaloom.loom.db.model.cluster.ClusterMember;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.page.Page;

@Singleton
public class ClusterDaoImpl extends AbstractJooqDao<Cluster> implements ClusterDao {

	/**
	 * Columns a producer's upsert must not touch.
	 *
	 * <p>
	 * The review verdict belongs to the reviewer, not to the node that proposed the cluster. Without this, re-running face detection over an asset
	 * would silently reset every cluster a human had already confirmed back to PENDING and drop the person link.
	 * </p>
	 *
	 * <p>
	 * {@code reviewed_at}/{@code reviewer_uuid} are here for the same reason and are why they exist at all (V2.88): {@code editor_uuid} is deliberately
	 * <em>not</em> preserved, because it is the producer's own provenance and moves with every run - which is precisely what used to erase the record of
	 * who attributed a face to a person.
	 * </p>
	 */
	private static final Set<String> REVIEW_COLUMNS = Set.of("status", "person_uuid", "reviewed_at", "reviewer_uuid");

	@Inject
	public ClusterDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Clusters";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqCluster.CLUSTER;
	}

	@Override
	protected Class<? extends Cluster> getPojoClass() {
		return ClusterImpl.class;
	}

	@Override
	public Cluster createCluster(UUID userUuid, String name, String type) {
		Cluster cluster = new ClusterImpl();
		cluster.setName(name);
		cluster.setType(type);
		cluster.setStatus(Cluster.STATUS_PENDING);
		setCreatorEditor(cluster, userUuid);
		return cluster;
	}

	@Override
	public Cluster createMachineCluster(String type, String nodeKind, UUID assetUuid, int clusterIndex) {
		Cluster cluster = new ClusterImpl();
		cluster.setType(type);
		cluster.setNodeKind(nodeKind);
		cluster.setAssetUuid(assetUuid);
		cluster.setClusterIndex(clusterIndex);
		cluster.setStatus(Cluster.STATUS_PENDING);
		// No name: a proposal has none until a reviewer supplies one. No creator/editor: a Cortex worker is not a user (V2.79).
		cluster.setCreated(Instant.now());
		cluster.setEdited(Instant.now());
		return cluster;
	}

	@Override
	public Cluster upsertCluster(Cluster cluster) {
		upsert(cluster, REVIEW_COLUMNS, CLUSTER.ASSET_UUID, CLUSTER.NODE_KIND, CLUSTER.CLUSTER_INDEX);
		return cluster;
	}

	@Override
	public int deleteStalePending(UUID assetUuid, String nodeKind, Collection<Integer> keptIndices) {
		Condition condition = CLUSTER.ASSET_UUID.eq(assetUuid)
			.and(CLUSTER.NODE_KIND.eq(nodeKind))
			.and(CLUSTER.STATUS.eq(JooqReviewStatus.PENDING));
		if (keptIndices != null && !keptIndices.isEmpty()) {
			condition = condition.and(CLUSTER.CLUSTER_INDEX.notIn(keptIndices));
		}
		return ctx().deleteFrom(CLUSTER).where(condition).execute();
	}

	@Override
	public void link(Cluster cluster, Embedding embedding) {
		link(cluster.getUuid(), embedding.getUuid(), null, ClusterMember.ORIGIN_AUTO);
	}

	@Override
	public void link(UUID clusterUuid, UUID embeddingUuid, Float confidence, String origin) {
		String resolvedOrigin = origin == null ? ClusterMember.ORIGIN_AUTO : origin;
		// Idempotent on the (embedding_uuid, cluster_uuid) primary key: re-running the clusterer re-states a membership it already recorded, and that
		// must refresh the confidence rather than fail the whole write.
		ctx().insertInto(EMBEDDING_CLUSTER)
			.set(EMBEDDING_CLUSTER.CLUSTER_UUID, clusterUuid)
			.set(EMBEDDING_CLUSTER.EMBEDDING_UUID, embeddingUuid)
			.set(EMBEDDING_CLUSTER.CONFIDENCE, confidence)
			.set(EMBEDDING_CLUSTER.ORIGIN, resolvedOrigin)
			.onConflict(EMBEDDING_CLUSTER.EMBEDDING_UUID, EMBEDDING_CLUSTER.CLUSTER_UUID)
			.doUpdate()
			.set(EMBEDDING_CLUSTER.CONFIDENCE, confidence)
			.set(EMBEDDING_CLUSTER.ORIGIN, resolvedOrigin)
			.execute();
	}

	@Override
	public void unlink(Cluster cluster, Embedding embedding) {
		ctx().deleteFrom(EMBEDDING_CLUSTER)
			.where(EMBEDDING_CLUSTER.CLUSTER_UUID.eq(cluster.getUuid())
				.and(EMBEDDING_CLUSTER.EMBEDDING_UUID.eq(embedding.getUuid())))
			.execute();
	}

	@Override
	public int unlinkAll(UUID clusterUuid) {
		return ctx().deleteFrom(EMBEDDING_CLUSTER)
			.where(EMBEDDING_CLUSTER.CLUSTER_UUID.eq(clusterUuid))
			.execute();
	}

	@Override
	public List<ClusterMember> listMembers(UUID clusterUuid) {
		// LEFT join on detection: an embedding may have been written without one (the column is nullable), and such a member must still be listed -
		// it simply has no crop to show.
		return ctx().select(EMBEDDING_CLUSTER.CLUSTER_UUID, EMBEDDING_CLUSTER.EMBEDDING_UUID, EMBEDDING_CLUSTER.CONFIDENCE,
			EMBEDDING_CLUSTER.ORIGIN, EMBEDDING_CLUSTER.CREATED,
			EMBEDDING.DETECTION_UUID, EMBEDDING.ASSET_UUID, EMBEDDING.FRAME_NUMBER,
			DETECTION.BBOX_X, DETECTION.BBOX_Y, DETECTION.BBOX_WIDTH, DETECTION.BBOX_HEIGHT)
			.from(EMBEDDING_CLUSTER)
			.join(EMBEDDING).on(EMBEDDING.UUID.eq(EMBEDDING_CLUSTER.EMBEDDING_UUID))
			.leftJoin(DETECTION).on(DETECTION.UUID.eq(EMBEDDING.DETECTION_UUID))
			.where(EMBEDDING_CLUSTER.CLUSTER_UUID.eq(clusterUuid))
			.orderBy(EMBEDDING.FRAME_NUMBER.asc(), DETECTION.BBOX_X.asc(), EMBEDDING_CLUSTER.EMBEDDING_UUID.asc())
			.fetch(this::mapMember);
	}

	@Override
	public long countMembers(UUID clusterUuid) {
		return ctx().fetchCount(EMBEDDING_CLUSTER, EMBEDDING_CLUSTER.CLUSTER_UUID.eq(clusterUuid));
	}

	@Override
	public Map<UUID, Long> countMembers(Collection<UUID> clusterUuids) {
		if (clusterUuids == null || clusterUuids.isEmpty()) {
			return Map.of();
		}
		return ctx().select(EMBEDDING_CLUSTER.CLUSTER_UUID, DSL.count())
			.from(EMBEDDING_CLUSTER)
			.where(EMBEDDING_CLUSTER.CLUSTER_UUID.in(clusterUuids))
			.groupBy(EMBEDDING_CLUSTER.CLUSTER_UUID)
			.fetch()
			.stream()
			.collect(Collectors.toMap(r -> r.get(EMBEDDING_CLUSTER.CLUSTER_UUID), r -> r.get(DSL.count()).longValue()));
	}

	@Override
	public List<Cluster> findByPerson(UUID personUuid) {
		return ctx().selectFrom(CLUSTER)
			.where(CLUSTER.PERSON_UUID.eq(personUuid))
			.orderBy(CLUSTER.CREATED.desc())
			.fetchInto(ClusterImpl.class)
			.stream()
			.map(c -> (Cluster) c)
			.toList();
	}

	@Override
	public List<Cluster> listByAsset(UUID assetUuid) {
		return ctx().selectFrom(CLUSTER)
			.where(CLUSTER.ASSET_UUID.eq(assetUuid))
			.orderBy(CLUSTER.CLUSTER_INDEX.asc())
			.fetchInto(ClusterImpl.class)
			.stream()
			.map(c -> (Cluster) c)
			.toList();
	}

	@Override
	public Page<Cluster> loadPage(String status, String type, UUID fromId, int pageSize) {
		// Bespoke rather than the inherited loadPage: AbstractJooqDao.getField casts every sort column to Field<UUID>, so it cannot order by created.
		Condition condition = DSL.noCondition();
		if (status != null) {
			condition = condition.and(CLUSTER.STATUS.eq(status(status)));
		}
		if (type != null) {
			condition = condition.and(CLUSTER.TYPE.eq(type));
		}
		long totalCount = ctx().fetchCount(CLUSTER, condition);

		if (fromId != null) {
			// Keyset seek in (created DESC, uuid DESC) order. A cursor row that has since been deleted yields no rows rather than silently
			// restarting from the top.
			LocalDateTime fromCreated = ctx().select(CLUSTER.CREATED)
				.from(CLUSTER)
				.where(CLUSTER.UUID.eq(fromId))
				.fetchOne(CLUSTER.CREATED);
			if (fromCreated == null) {
				return new Page<>(pageSize, totalCount, List.<Cluster> of());
			}
			condition = condition.and(DSL.row(CLUSTER.CREATED, CLUSTER.UUID).lt(DSL.row(fromCreated, fromId)));
		}

		List<Cluster> list = ctx().selectFrom(CLUSTER)
			.where(condition)
			.orderBy(CLUSTER.CREATED.desc(), CLUSTER.UUID.desc())
			.limit(pageSize)
			.fetchInto(ClusterImpl.class)
			.stream()
			.map(c -> (Cluster) c)
			.toList();

		return new Page<>(pageSize, totalCount, list);
	}

	@Override
	public Cluster updateStatus(UUID clusterUuid, String status, UUID personUuid, UUID reviewerUuid) {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		// The author is written here rather than in ClusterEndpointService so every caller records one - including the bulk review endpoint the queue
		// will eventually grow. edited/editor_uuid are set alongside for continuity, but they are not the audit trail: the producing node overwrites
		// them on its next run, which is why V2.88 added the reviewer pair.
		var step = ctx().update(CLUSTER)
			.set(CLUSTER.STATUS, status(status))
			.set(CLUSTER.REVIEWED_AT, now)
			.set(CLUSTER.REVIEWER_UUID, reviewerUuid)
			.set(CLUSTER.EDITED, now)
			.set(CLUSTER.EDITOR_UUID, reviewerUuid);
		if (personUuid != null) {
			step = step.set(CLUSTER.PERSON_UUID, personUuid);
		}
		step.where(CLUSTER.UUID.eq(clusterUuid)).execute();
		return load(clusterUuid);
	}

	@Override
	public Cluster confirm(UUID clusterUuid, UUID personUuid, PersonDraft draft, UUID reviewerUuid) {
		// One transaction: a person created without its cluster pointer is an orphan, and a CONFIRMED cluster without a person is a verdict with no
		// subject. Every statement below runs on DSL.using(cfg) - ctx() would take its own connection and fall outside the transaction.
		return ctx().transactionResult(cfg -> {
			DSLContext tx = DSL.using(cfg);
			UUID resolvedPerson = personUuid;
			if (resolvedPerson == null) {
				resolvedPerson = tx.insertInto(PERSON)
					.set(PERSON.ALIAS, draft == null ? null : draft.alias())
					.set(PERSON.FIRSTNAME, draft == null ? null : draft.firstname())
					.set(PERSON.LASTNAME, draft == null ? null : draft.lastname())
					.set(PERSON.CREATOR_UUID, reviewerUuid)
					.set(PERSON.EDITOR_UUID, reviewerUuid)
					.returning(PERSON.UUID)
					.fetchOne(PERSON.UUID);
			}
			LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
			// reviewed_at/reviewer_uuid land in the same statement as the verdict they belong to: an attribution of a face to a named person is
			// biometric, and it must not be possible to read one back without its author (V2.88).
			tx.update(CLUSTER)
				.set(CLUSTER.STATUS, JooqReviewStatus.CONFIRMED)
				.set(CLUSTER.PERSON_UUID, resolvedPerson)
				.set(CLUSTER.REVIEWED_AT, now)
				.set(CLUSTER.REVIEWER_UUID, reviewerUuid)
				.set(CLUSTER.EDITED, now)
				.set(CLUSTER.EDITOR_UUID, reviewerUuid)
				.where(CLUSTER.UUID.eq(clusterUuid))
				.execute();
			return tx.selectFrom(CLUSTER)
				.where(CLUSTER.UUID.eq(clusterUuid))
				.fetchOneInto(ClusterImpl.class);
		});
	}

	private static JooqReviewStatus status(String status) {
		return status == null ? JooqReviewStatus.PENDING : JooqReviewStatus.lookupLiteral(status);
	}

	private ClusterMember mapMember(Record r) {
		return new ClusterMemberImpl()
			.setClusterUuid(r.get(EMBEDDING_CLUSTER.CLUSTER_UUID))
			.setEmbeddingUuid(r.get(EMBEDDING_CLUSTER.EMBEDDING_UUID))
			.setConfidence(r.get(EMBEDDING_CLUSTER.CONFIDENCE))
			.setOrigin(r.get(EMBEDDING_CLUSTER.ORIGIN))
			.setCreated(toInstant(r.get(EMBEDDING_CLUSTER.CREATED)))
			.setDetectionUuid(r.get(EMBEDDING.DETECTION_UUID))
			.setAssetUuid(r.get(EMBEDDING.ASSET_UUID))
			.setFrameNumber(r.get(EMBEDDING.FRAME_NUMBER))
			.setBboxX(r.get(DETECTION.BBOX_X))
			.setBboxY(r.get(DETECTION.BBOX_Y))
			.setBboxWidth(r.get(DETECTION.BBOX_WIDTH))
			.setBboxHeight(r.get(DETECTION.BBOX_HEIGHT));
	}

	private static Instant toInstant(LocalDateTime ldt) {
		return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
	}

}
