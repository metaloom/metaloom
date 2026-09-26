package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterConfirmRequest;
import io.metaloom.loom.rest.model.cluster.ClusterCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterMemberListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.cluster.ClusterUpdateRequest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonResponse;

public class ClusterEndpointTest extends AbstractCRUDEndpointTest {

	private ClusterResponse createTestCluster(LoomHttpClient client) throws LoomClientException {
		ClusterCreateRequest request = new ClusterCreateRequest();
		request.setName("test-cluster");
		return client.createCluster(request).sync().body();
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		ClusterResponse created = createTestCluster(client);
		ClusterResponse cluster = client.loadCluster(created.getUuid()).sync().body();
		assertThat(cluster).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		ClusterCreateRequest request = new ClusterCreateRequest();
		request.setName("dummy name");
		ClusterResponse cluster = client.createCluster(request).sync().body();
		assertThat(cluster).isValid();

		ClusterResponse cluster2 = client.loadCluster(cluster.getUuid()).sync().body();
		assertThat(cluster).matches(cluster2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		ClusterResponse created = createTestCluster(client);
		client.deleteCluster(created.getUuid()).sync().body();
		expect(404, "Not Found", client.loadCluster(created.getUuid()));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		ClusterResponse created = createTestCluster(client);
		ClusterUpdateRequest update = new ClusterUpdateRequest();
		update.setName("updated-name");
		ClusterResponse response = client.updateCluster(created.getUuid(), update).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			ClusterCreateRequest request = new ClusterCreateRequest();
			request.setName("dummy name " + i);
			client.createCluster(request).sync().body();
		}
		ClusterListResponse list = client.listClusters().sync().body();
		assertThat(list).isValid().hasPerPage(25);
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		ClusterCreateRequest request = new ClusterCreateRequest();
		request.setName("perm-check");
		return client.createCluster(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadCluster(CLUSTER_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listClusters();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteCluster(CLUSTER_UUID);
	}

	// ---------------------------------------------------------------------------------------------
	// The review loop: propose -> confirm/reject -> find back by person.
	// ---------------------------------------------------------------------------------------------

	/**
	 * A producer writes the subjects it found, and the write is idempotent: running it twice leaves one set, not two.
	 */
	@Test
	public void testBulkCreateForAssetIsIdempotent() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			ClusterBulkResponse first = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body();
			assertEquals(1, first.getCreated(), "One cluster written");

			ClusterBulkResponse second = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.5f)).sync().body();
			assertEquals(1, second.getCreated());
			assertEquals(first.getClusters().get(0).getUuid(), second.getClusters().get(0).getUuid(),
				"The same (asset, nodeKind, clusterIndex) must rewrite the same row");

			ClusterListResponse assetClusters = client.listAssetClusters(ASSET_UUID).sync().body();
			assertEquals(1, assetClusters.getData().size(), "A re-run must not append a second set");
			assertEquals(0.5f, assetClusters.getData().get(0).getScore(), 0.0001f, "The producer's own payload is updated");
		}
	}

	/**
	 * A re-run that finds fewer subjects retires the proposals it no longer makes.
	 */
	@Test
	public void testBulkCreatePrunesStalePendingClusters() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			ClusterBulkCreateRequest three = new ClusterBulkCreateRequest();
			for (int i = 0; i < 3; i++) {
				three.add(new ClusterCreateItem().setType("face").setNodeKind("facedetect").setClusterIndex(i));
			}
			client.bulkCreateAssetClusters(ASSET_UUID, three).sync().body();
			assertEquals(3, client.listAssetClusters(ASSET_UUID).sync().body().getData().size());

			ClusterBulkResponse shrunk = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body();

			assertEquals(2, shrunk.getPruned(), "The two proposals no longer made must be retired");
			assertEquals(1, client.listAssetClusters(ASSET_UUID).sync().body().getData().size());
		}
	}

	/**
	 * Confirming without a person uuid creates the person and links it; the inverse lookup then finds the cluster back.
	 */
	@Test
	public void testConfirmCreatesPersonAndLinksBack() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			ClusterResponse proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);
			assertEquals("PENDING", proposed.getReviewStatus(), "A machine proposal starts pending");

			ClusterConfirmRequest confirm = new ClusterConfirmRequest().setAlias("Anna Meyer").setFirstname("Anna").setLastname("Meyer");
			ClusterResponse confirmed = client.confirmCluster(proposed.getUuid(), confirm).sync().body();

			assertEquals("CONFIRMED", confirmed.getReviewStatus());
			assertNotNull(confirmed.getPersonUuid(), "A person must have been created and linked");

			ClusterListResponse ofPerson = client.listPersonClusters(java.util.UUID.fromString(confirmed.getPersonUuid())).sync().body();
			assertEquals(1, ofPerson.getData().size(), "The inverse lookup must find the cluster");
			assertEquals(confirmed.getUuid(), ofPerson.getData().get(0).getUuid());
		}
	}

	/**
	 * Confirming against an existing person links rather than creating a second one.
	 */
	@Test
	public void testConfirmLinksExistingPerson() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = client.createPerson(new PersonCreateRequest().setAlias("Existing Person")).sync().body();
			ClusterResponse proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);

			ClusterResponse confirmed = client.confirmCluster(proposed.getUuid(),
				new ClusterConfirmRequest().setPersonUuid(person.getUuid().toString())).sync().body();

			assertEquals("CONFIRMED", confirmed.getReviewStatus());
			assertEquals(person.getUuid().toString(), confirmed.getPersonUuid());
		}
	}

	/**
	 * Detaching is the inverse of confirming, and the only way back from a wrong attribution.
	 *
	 * <p>
	 * Reviewers stack clusters onto a person by dragging one card onto another, and they will
	 * sometimes stack the wrong one. Before this route the only ways out were {@code update} - which
	 * writes name/type/meta and cannot clear the person - and {@code reject}, which records that the
	 * cluster is not a real subject. Recording "this is not a face worth keeping" because it was
	 * attributed to the wrong person would be a false statement about somebody's face.
	 * </p>
	 */
	@Test
	public void testDetachPersonReturnsTheClusterToTheQueue() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = client.createPerson(new PersonCreateRequest().setAlias("Wrongly Attributed")).sync().body();
			ClusterResponse proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);
			ClusterResponse confirmed = client.confirmCluster(proposed.getUuid(),
				new ClusterConfirmRequest().setPersonUuid(person.getUuid().toString())).sync().body();
			assertEquals(person.getUuid().toString(), confirmed.getPersonUuid());

			ClusterResponse detached = client.detachClusterPerson(proposed.getUuid()).sync().body();

			assertNull(detached.getPersonUuid(), "the attribution is gone");
			// Back in the queue, not left CONFIRMED-with-nobody: a verdict about no subject would
			// never be shown for review again.
			assertEquals("PENDING", detached.getReviewStatus());
			// The person survives - it may hold other clusters, and deleting a directory entry is a
			// much larger action than undoing one attribution.
			assertNotNull(client.loadPerson(person.getUuid()).sync().body(), "the person is not deleted");
		}
	}

	@Test
	public void testDetachPersonRequiresUpdatePermission() throws Exception {
		ClusterResponse proposed;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			proposed = admin.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);
		}
		// READ_CLUSTER only: seeing a cluster must not carry the right to change its attribution.
		try (LoomHttpClient reader = loginClientWith("cluster-reader", Permission.READ_CLUSTER)) {
			expect(403, "Forbidden", reader.detachClusterPerson(proposed.getUuid()));
		}
	}

	/**
	 * Rejecting records the verdict without deleting the record of it.
	 */
	@Test
	public void testRejectSetsStatus() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			ClusterResponse proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);

			ClusterResponse rejected = client.rejectCluster(proposed.getUuid()).sync().body();

			assertEquals("REJECTED", rejected.getReviewStatus());
			assertNotNull(client.loadCluster(proposed.getUuid()).sync().body(), "A rejected cluster is a record, not a deletion");
		}
	}

	/**
	 * A confirmed cluster survives the producer running again - the node owns its proposals, not the verdicts on them.
	 */
	@Test
	public void testReRunDoesNotReopenAConfirmedCluster() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			ClusterResponse proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);
			client.confirmCluster(proposed.getUuid(), new ClusterConfirmRequest().setAlias("Anna Meyer")).sync().body();

			client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.7f)).sync().body();

			ClusterResponse reloaded = client.loadCluster(proposed.getUuid()).sync().body();
			assertEquals("CONFIRMED", reloaded.getReviewStatus(), "A re-run must not reopen a settled decision");
			assertNotNull(reloaded.getPersonUuid(), "A re-run must not drop the person link");
		}
	}

	/**
	 * Confirming records which human attributed the face, and when - durably.
	 *
	 * <p>
	 * The verdict used to be attributed only through {@code editor_uuid}, which the producing node rewrites on every pass, so the answer to "who said
	 * this is Anna?" survived until the next pipeline run and no longer. Face data is biometric; the second half of this test is the point, not a
	 * flourish.
	 * </p>
	 */
	@Test
	public void testConfirmRecordsTheReviewer() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			ClusterResponse proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);
			assertNull(proposed.getReviewedAt(), "A machine proposal nobody has decided on has no review timestamp");
			assertNull(proposed.getReviewerUuid());

			// With a name, so the confirmation also takes the trailing dao().update(cluster) path - a whole-POJO write that must round-trip the two
			// new fields rather than nulling them on the way back out.
			ClusterResponse confirmed = client.confirmCluster(proposed.getUuid(),
				new ClusterConfirmRequest().setAlias("Anna Meyer").setName("Anna's faces")).sync().body();

			assertNotNull(confirmed.getReviewedAt(), "The verdict is timestamped");
			assertEquals(ADMIN_UUID.toString(), confirmed.getReviewerUuid(), "and attributed to the user who made it");

			// The node runs again over the same asset, as it does on every pipeline pass.
			client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.7f)).sync().body();

			ClusterResponse reloaded = client.loadCluster(proposed.getUuid()).sync().body();
			assertEquals(ADMIN_UUID.toString(), reloaded.getReviewerUuid(), "A re-run must not erase who attributed the face");
			// To the millisecond, not to the microsecond. Confirming with a name runs a trailing whole-POJO dao().update(), and that path maps the
			// POJO's Instant through jOOQ's LocalDateTime conversion, which truncates sub-millisecond digits. Generic and long-standing - "created"
			// and "edited" have always behaved this way - so tightening this to exact equality would be pinning a jOOQ detail, not this feature.
			assertEquals(millis(confirmed.getReviewedAt()), millis(reloaded.getReviewedAt()), "nor when they did");
		}
	}

	/**
	 * Rejecting records its author too - the half with no person link to fall back on.
	 */
	@Test
	public void testRejectRecordsTheReviewer() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			ClusterResponse proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);

			ClusterResponse rejected = client.rejectCluster(proposed.getUuid()).sync().body();

			assertEquals("REJECTED", rejected.getReviewStatus());
			assertNotNull(rejected.getReviewedAt(), "A rejection is a decision and is timestamped");
			assertEquals(ADMIN_UUID.toString(), rejected.getReviewerUuid(), "and attributed");

			client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.7f)).sync().body();

			assertEquals(ADMIN_UUID.toString(), client.loadCluster(proposed.getUuid()).sync().body().getReviewerUuid(),
				"A re-run must not erase who rejected it");
		}
	}

	/**
	 * The review queue filters by status and type.
	 */
	@Test
	public void testListFiltersByStatusAndType() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			ClusterResponse proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);

			assertTrue(contains(client.listClusters("PENDING", "face").sync().body(), proposed.getUuid()),
				"The pending face cluster must be in the queue");

			client.rejectCluster(proposed.getUuid()).sync().body();

			assertFalse(contains(client.listClusters("PENDING", "face").sync().body(), proposed.getUuid()),
				"A decided cluster must leave the pending queue");
			assertTrue(contains(client.listClusters("REJECTED", "face").sync().body(), proposed.getUuid()),
				"and appear under its new status");

			expect(400, "Bad Request", client.listClusters("NONSENSE", null));
		}
	}

	/**
	 * Confirming needs {@code CREATE_PERSON} only when it actually creates a person.
	 *
	 * <p>
	 * This is the whole point of the request-dependent permission set: a reviewer can be trusted to attribute faces to people who already exist without
	 * being able to add new ones to the directory. Nothing else in the tree exercises that.
	 * </p>
	 */
	@Test
	public void testConfirmRequiresCreatePersonOnlyWhenCreating() throws Exception {
		ClusterResponse proposed;
		PersonResponse existing;
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			existing = client.createPerson(new PersonCreateRequest().setAlias("Already Known")).sync().body();
			proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);
		}

		try (LoomHttpClient client = loom.httpClient()) {
			// joedoe may read and update clusters, and may read persons - but may not create one.
			loginReviewer(client);

			ClusterResponse linked = client.confirmCluster(proposed.getUuid(),
				new ClusterConfirmRequest().setPersonUuid(existing.getUuid().toString())).sync().body();
			assertEquals("CONFIRMED", linked.getReviewStatus(), "Linking an existing person needs only UPDATE_CLUSTER");

			expect(403, "Forbidden", client.confirmCluster(proposed.getUuid(), new ClusterConfirmRequest().setAlias("Somebody New")));
		}
	}

	/**
	 * Confirming without a person uuid and without any name has nothing to create the person from.
	 */
	@Test
	public void testConfirmWithoutPersonOrNamesIsRejected() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			ClusterResponse proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);

			expect(400, "Bad Request", client.confirmCluster(proposed.getUuid(), new ClusterConfirmRequest()));
		}
	}

	@Test
	public void testMembersOfAClusterWithNoMembersIsEmpty() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			ClusterResponse proposed = client.bulkCreateAssetClusters(ASSET_UUID, bulkRequest(0.9f)).sync().body().getClusters().get(0);

			ClusterMemberListResponse members = client.listClusterMembers(proposed.getUuid()).sync().body();

			assertEquals(0, members.getTotal());
			assertEquals(0L, proposed.getMemberCount());
		}
	}

	/**
	 * A per-asset face cluster describes that asset and is meaningless without it (V2.79 {@code cluster.asset_uuid ON DELETE CASCADE}). Deleting the
	 * asset must remove its cluster while a cluster confirmed onto the same person, but proposed for a different asset, survives untouched - the
	 * person's own directory entry is not the thing being deleted here.
	 */
	@Test
	public void testDeletingAssetCascadesItsCluster() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			io.metaloom.loom.db.model.asset.Asset victim = seedAsset("cluster-victim.jpg");
			io.metaloom.loom.db.model.asset.Asset bystander = seedAsset("cluster-bystander.jpg");

			PersonResponse person = client.createPerson(new PersonCreateRequest().setAlias("cluster-cascade-person")).sync().body();

			ClusterResponse victimCluster = client.bulkCreateAssetClusters(victim.getUuid(), bulkRequest(0.9f)).sync().body().getClusters().get(0);
			client.confirmCluster(victimCluster.getUuid(), new ClusterConfirmRequest().setPersonUuid(person.getUuid().toString())).sync().body();

			ClusterResponse bystanderCluster = client.bulkCreateAssetClusters(bystander.getUuid(), bulkRequest(0.9f)).sync().body().getClusters()
				.get(0);
			client.confirmCluster(bystanderCluster.getUuid(), new ClusterConfirmRequest().setPersonUuid(person.getUuid().toString())).sync().body();

			daos().assetDao().delete(victim.getUuid());

			expect(404, "Not Found", client.loadCluster(victimCluster.getUuid()));
			assertNotNull(client.loadCluster(bystanderCluster.getUuid()).sync().body(),
				"a cluster proposed for a different asset must survive");
			assertNotNull(client.loadPerson(person.getUuid()).sync().body(), "the person the clusters were confirmed onto must survive");
			assertEquals(1, client.listPersonClusters(person.getUuid()).sync().body().getData().size(),
				"only the surviving asset's cluster remains linked to the person");
		}
	}

	private io.metaloom.loom.db.model.asset.Asset seedAsset(String filename) {
		DaoCollection daos = daos();
		io.metaloom.loom.db.model.asset.Asset asset = daos.assetDao().createAsset(adminUuid(),
			io.metaloom.utils.hash.SHA512.fromString(java.util.UUID.randomUUID().toString().replace("-", "").repeat(4)),
			"image/jpeg", filename, "/media/" + filename, 42L);
		daos.assetDao().store(asset);
		return asset;
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Whether the list holds the given cluster.
	 *
	 * <p>
	 * Tolerates a null {@code data}: {@code AbstractListResponse} initialises the array lazily on the first add, so every list endpoint in the tree
	 * answers an empty result with no array at all rather than with {@code []}. An empty review queue is the normal case here, not an edge one.
	 * </p>
	 */
	private static boolean contains(ClusterListResponse list, java.util.UUID uuid) {
		return list.getData() != null && list.getData().stream().anyMatch(c -> uuid.equals(c.getUuid()));
	}

	/** An ISO instant truncated to milliseconds - the precision that survives a whole-POJO {@code dao().update()}. */
	private static java.time.Instant millis(String isoInstant) {
		assertNotNull(isoInstant, "expected a timestamp");
		return java.time.Instant.parse(isoInstant).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
	}

	/** One machine-proposed face cluster for {@code ASSET_UUID}, at index 0. */
	private ClusterBulkCreateRequest bulkRequest(float score) {
		return new ClusterBulkCreateRequest()
			.add(new ClusterCreateItem()
				.setType("face")
				.setNodeKind("facedetect")
				.setProducerVersion("1/inspireface-pikachu-r18")
				.setClusterIndex(0)
				.setScore(score)
				.setModel("inspireface-pikachu-r18")
				.setDimensions(512));
	}

	/**
	 * Log in as a reviewer: may read and decide on clusters, and read persons, but may not create one.
	 *
	 * <p>
	 * Granted through a group and a role rather than directly, because {@code user_permission} allows only one direct grant per user.
	 * </p>
	 */
	private void loginReviewer(LoomHttpClient client) throws LoomClientException {
		DaoCollection daos = loom.internal().daos();
		User joedoe = daos.userDao().load(USER_UUID);
		Role role = daos.roleDao().createRole(ADMIN_UUID, "cluster-review-role");
		daos.roleDao().store(role);
		for (Permission perm : List.of(Permission.READ_CLUSTER, Permission.UPDATE_CLUSTER, Permission.READ_PERSON)) {
			daos.permissionDao().grantRolePermission(role.getUuid(), perm);
		}
		Group group = daos.groupDao().create(joedoe, "cluster-review-group");
		daos.groupDao().store(group);
		daos.groupDao().addRoleToGroup(group, role);
		daos.groupDao().addUserToGroup(group, joedoe);

		AuthLoginResponse loginResponse = client.login("joedoe", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

}
