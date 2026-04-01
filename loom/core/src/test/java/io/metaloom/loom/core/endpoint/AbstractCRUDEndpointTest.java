package io.metaloom.loom.core.endpoint;

import org.junit.jupiter.api.Test;

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

}
