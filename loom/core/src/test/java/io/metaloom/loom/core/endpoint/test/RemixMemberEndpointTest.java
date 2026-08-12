package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.READ_REMIX;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_REMIX;
import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static io.metaloom.loom.test.data.TestValues.ASSET_UUID;
import static io.metaloom.loom.test.data.TestValues.REMIX_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.remix.RemixCreateRequest;
import io.metaloom.loom.rest.model.remix.RemixMemberListResponse;
import io.metaloom.loom.rest.model.remix.RemixMemberRequest;
import io.metaloom.loom.rest.model.remix.RemixMemberResponse;
import io.metaloom.loom.rest.model.remix.RemixResponse;

/**
 * The nested {@code /remixes/:uuid/assets} and {@code /assets/:uuid/remixes} routes.
 */
public class RemixMemberEndpointTest extends AbstractEndpointTest {

	@Test
	public void testListMembers() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			RemixMemberListResponse members = client.listRemixAssets(REMIX_UUID).sync().body();
			assertEquals(2, members.getData().size(), "The fixture remix holds two assets");

			RemixMemberResponse source = members.getData().stream()
				.filter(m -> RemixMemberResponse.ROLE_SOURCE.equals(m.getRole()))
				.findFirst().orElseThrow();
			assertEquals(ASSET_UUID, source.getAssetUuid(), "The fixture asset is the remix's source");
			assertTrue(source.getFilename() != null && !source.getFilename().isBlank(),
				"The asset side of the join should be projected so a card can render without a second call");
		}
	}

	@Test
	public void testAddAndRemoveMember() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			UUID remixUuid = client.createRemix(remix("membership")).sync().body().getUuid();

			RemixResponse afterAdd = client.addRemixAssets(remixUuid, List.of(ASSET_UUID)).sync().body();
			assertThat(afterAdd).isValid().hasMemberCount(1);

			client.removeRemixAsset(remixUuid, ASSET_UUID).sync();
			assertThat(client.loadRemix(remixUuid).sync().body()).hasMemberCount(0);
		}
	}

	/** Re-adding an existing member is a no-op success, not a duplicate-key 500. */
	@Test
	public void testAddingAnExistingMemberIsIdempotent() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			UUID remixUuid = client.createRemix(remix("idempotent")).sync().body().getUuid();

			client.addRemixAssets(remixUuid, List.of(ASSET_UUID)).sync();
			RemixResponse again = client.addRemixAssets(remixUuid, List.of(ASSET_UUID)).sync().body();

			assertThat(again).hasMemberCount(1);
		}
	}

	@Test
	public void testRemovingANonMemberIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			UUID remixUuid = client.createRemix(remix("not-a-member")).sync().body().getUuid();
			expect(404, "Not Found", client.removeRemixAsset(remixUuid, ASSET_UUID));
		}
	}

	@Test
	public void testAddingAnUnknownAssetIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			expect(404, "Not Found", client.addRemixAssets(REMIX_UUID, List.of(UUID.randomUUID())));
		}
	}

	@Test
	public void testAddingToAnUnknownRemixIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			expect(404, "Not Found", client.addRemixAssets(UUID.randomUUID(), List.of(ASSET_UUID)));
		}
	}

	@Test
	public void testAddingAnEmptyListIs400() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			expect(400, "Bad Request", client.addRemixAssets(REMIX_UUID, new RemixMemberRequest()));
		}
	}

	@Test
	public void testSetSource() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			UUID remixUuid = client.createRemix(remix("set-source")).sync().body().getUuid();
			client.addRemixAssets(remixUuid, List.of(ASSET_UUID)).sync();

			RemixResponse response = client.setRemixSource(remixUuid, ASSET_UUID).sync().body();
			assertThat(response).isValid().hasSource(ASSET_UUID);
		}
	}

	/**
	 * Naming a non-member as the source is a 400.
	 *
	 * <p>
	 * The DAO refuses it with an {@link IllegalArgumentException}; without the translation in the
	 * endpoint service that would surface as a 500 for what is plainly a caller mistake.
	 * </p>
	 */
	@Test
	public void testSetSourceRejectsANonMember() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			UUID remixUuid = client.createRemix(remix("bad-source")).sync().body().getUuid();
			expect(400, "Bad Request", client.setRemixSource(remixUuid, ASSET_UUID));
		}
	}

	@Test
	public void testListRemixesOfAsset() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			assertTrue(client.listAssetRemixes(ASSET_UUID).sync().body().getData().stream()
				.anyMatch(r -> REMIX_UUID.equals(r.getUuid())),
				"The fixture remix should be reachable from the asset it holds");
		}
	}

	// --- Permissions ---

	@Test
	public void testAddingMembersRequiresUpdateAndAssetRead() throws Exception {
		try (LoomHttpClient client = loginClientWith("member-adder-no-asset", UPDATE_REMIX)) {
			expect(403, "Forbidden", client.addRemixAssets(REMIX_UUID, List.of(ASSET_UUID)));
		}
		try (LoomHttpClient client = loginClientWith("member-adder-no-update", READ_ASSET, READ_REMIX)) {
			expect(403, "Forbidden", client.addRemixAssets(REMIX_UUID, List.of(ASSET_UUID)));
		}
		try (LoomHttpClient client = loginClientWith("member-adder", UPDATE_REMIX, READ_ASSET, READ_REMIX)) {
			assertThat(client.addRemixAssets(REMIX_UUID, List.of(ASSET_UUID)).sync().body()).isValid();
		}
	}

	@Test
	public void testRemovingAMemberRequiresUpdatePermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.removeRemixAsset(REMIX_UUID, ASSET_UUID));
		}
	}

	@Test
	public void testListingRemixesOfAnAssetRequiresBothPermissions() throws Exception {
		try (LoomHttpClient client = loginClientWith("asset-remix-lister-no-remix", READ_ASSET)) {
			expect(403, "Forbidden", client.listAssetRemixes(ASSET_UUID));
		}
		try (LoomHttpClient client = loginClientWith("asset-remix-lister", READ_ASSET, READ_REMIX)) {
			assertTrue(client.listAssetRemixes(ASSET_UUID).sync().body().getData().size() >= 1);
		}
	}

	private static RemixCreateRequest remix(String name) {
		return new RemixCreateRequest().setName(name);
	}

}
