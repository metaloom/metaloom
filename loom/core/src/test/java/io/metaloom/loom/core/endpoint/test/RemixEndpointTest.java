package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_REMIX;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.READ_REMIX;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_REMIX;
import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static io.metaloom.loom.test.data.TestValues.ASSET_UUID;
import static io.metaloom.loom.test.data.TestValues.REMIX_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.remix.RemixCreateRequest;
import io.metaloom.loom.rest.model.remix.RemixListResponse;
import io.metaloom.loom.rest.model.remix.RemixResponse;
import io.metaloom.loom.rest.model.remix.RemixUpdateRequest;

public class RemixEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		RemixResponse remix = client.loadRemix(REMIX_UUID).sync().body();
		assertThat(remix).isValid().hasName("Fixture Remix").hasMemberCount(2);
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		RemixCreateRequest request = new RemixCreateRequest().setName("dummy remix");
		RemixResponse remix = client.createRemix(request).sync().body();
		assertThat(remix).isValid().hasName("dummy remix").hasMemberCount(0).hasNoSource();

		RemixResponse loaded = client.loadRemix(remix.getUuid()).sync().body();
		assertEquals(remix.getUuid(), loaded.getUuid(), "The created remix should be readable back");
		assertEquals(remix.getName(), loaded.getName());
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteRemix(REMIX_UUID).sync().body();
		expect(404, "Not Found", client.loadRemix(REMIX_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		RemixUpdateRequest update = new RemixUpdateRequest().setName("updated-name").setDescription("updated-description");
		RemixResponse response = client.updateRemix(REMIX_UUID, update).sync().body();
		assertThat(response).isValid().hasName("updated-name").hasDescription("updated-description");
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			client.createRemix(new RemixCreateRequest().setName("dummy remix " + i)).sync().body();
		}
		RemixListResponse list = client.listRemixes().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

	/**
	 * Creating a remix with its members in one call is the shape the UI's "combine into remix" uses.
	 */
	@Test
	public void testCreateWithMembers() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			RemixCreateRequest request = new RemixCreateRequest()
				.setName("with members")
				.setSourceAssetUuid(ASSET_UUID);
			request.add(ASSET_UUID);

			RemixResponse remix = client.createRemix(request).sync().body();
			assertThat(remix).isValid().hasMemberCount(1).hasSource(ASSET_UUID);
		}
	}

	/** A source that is not among the listed assets is a bad request, not a 500. */
	@Test
	public void testCreateRejectsSourceOutsideTheMemberList() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			RemixCreateRequest request = new RemixCreateRequest()
				.setName("bad source")
				.setSourceAssetUuid(UUID.randomUUID());
			request.add(ASSET_UUID);

			expect(400, "Bad Request", client.createRemix(request));
		}
	}

	/** So is the same asset listed twice: the idempotent insert would swallow it silently. */
	@Test
	public void testCreateRejectsDuplicateMembers() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			RemixCreateRequest request = new RemixCreateRequest().setName("duplicate members");
			request.add(ASSET_UUID);
			request.add(ASSET_UUID);

			expect(400, "Bad Request", client.createRemix(request));
		}
	}

	@Test
	public void testCreateRejectsBlankName() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			expect(400, "Bad Request", client.createRemix(new RemixCreateRequest().setName("  ")));
		}
	}

	@Test
	public void testLoadUnknownRemix() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			expect(404, "Not Found", client.loadRemix(UUID.randomUUID()));
		}
	}

	/**
	 * Update has no generic 403 case in {@link AbstractCRUDEndpointTest}, so it gets one here.
	 */
	@Test
	public void testUpdateRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.updateRemix(REMIX_UUID, new RemixUpdateRequest().setName("nope")));
		}
	}

	/**
	 * Creating a remix needs READ_ASSET as well as CREATE_REMIX.
	 *
	 * <p>
	 * The create request may carry member uuids, so a caller holding only CREATE_REMIX could otherwise
	 * probe which asset uuids exist by watching for 404 versus 201.
	 * </p>
	 */
	@Test
	public void testCreateRequiresAssetPermissionToo() throws Exception {
		try (LoomHttpClient client = loginClientWith("remixer-without-read-asset", CREATE_REMIX)) {
			expect(403, "Forbidden", client.createRemix(new RemixCreateRequest().setName("no asset read")));
		}
		try (LoomHttpClient client = loginClientWith("remixer-with-read-asset", CREATE_REMIX, READ_ASSET, READ_REMIX)) {
			RemixResponse remix = client.createRemix(new RemixCreateRequest().setName("granted")).sync().body();
			assertEquals("granted", remix.getName());
		}
	}

	/**
	 * Listing a remix's members exposes asset filenames and hashes, so it needs READ_ASSET on top of
	 * READ_REMIX. Reading the remix row itself does not.
	 */
	@Test
	public void testListingMembersRequiresAssetPermissionToo() throws Exception {
		try (LoomHttpClient client = loginClientWith("remix-reader-only", READ_REMIX)) {
			assertThat(client.loadRemix(REMIX_UUID).sync().body()).isValid();
			expect(403, "Forbidden", client.listRemixAssets(REMIX_UUID));
		}
		try (LoomHttpClient client = loginClientWith("remix-and-asset-reader", READ_REMIX, READ_ASSET)) {
			assertEquals(2, client.listRemixAssets(REMIX_UUID).sync().body().getData().size());
		}
	}

	@Test
	public void testUpdateRequiresOnlyUpdatePermission() throws Exception {
		try (LoomHttpClient client = loginClientWith("remix-updater", UPDATE_REMIX)) {
			client.updateRemix(REMIX_UUID, new RemixUpdateRequest().setName("renamed by updater")).sync().body();
		}
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		return client.createRemix(new RemixCreateRequest().setName("dummy remix"));
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadRemix(REMIX_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listRemixes();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteRemix(REMIX_UUID);
	}

}
