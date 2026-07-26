package io.metaloom.loom.core.endpoint;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;

public abstract class AbstractCRUDEndpointTest extends AbstractEndpointTest implements CRUDEndpointTestcases {

	@Test
	@Override
	public void testRead() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			testRead(client);
		}
	}

	protected abstract void testRead(LoomHttpClient client) throws Exception;

	@Test
	@Override
	public void testCreate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			testCreate(client);
		}
	}

	protected abstract void testCreate(LoomHttpClient client) throws Exception;

	@Test
	@Override
	public void testDelete() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			testDelete(client);
		}
	}

	protected abstract void testDelete(LoomHttpClient client) throws Exception;

	@Test
	@Override
	public void testUpdate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			testUpdate(client);
		}
	}

	protected abstract void testUpdate(LoomHttpClient client) throws Exception;

	@Test
	@Override
	public void testReadPaged() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			testReadPage(client);
		}
	}

	protected abstract void testReadPage(LoomHttpClient client) throws Exception;

	// --- RBAC permission-denied cases ---
	// Each op runs as a freshly provisioned user that holds no permissions and must be rejected with HTTP 403.
	// checkPerm runs before the DAO loader, so a 403 fires even for a non-existent target — the request builders
	// below only need to be syntactically valid, they do not require seeded data.

	@Test
	@Override
	public void testCreateRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", createRequest(client));
		}
	}

	protected abstract LoomClientRequest<?> createRequest(LoomHttpClient client) throws Exception;

	@Test
	@Override
	public void testReadRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", loadRequest(client));
		}
	}

	protected abstract LoomClientRequest<?> loadRequest(LoomHttpClient client) throws Exception;

	@Test
	@Override
	public void testListRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", listRequest(client));
		}
	}

	protected abstract LoomClientRequest<?> listRequest(LoomHttpClient client) throws Exception;

	@Test
	@Override
	public void testDeleteRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", deleteRequest(client));
		}
	}

	protected abstract LoomClientRequest<?> deleteRequest(LoomHttpClient client) throws Exception;

}
