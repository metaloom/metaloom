package io.metaloom.loom.core.endpoint;

import org.junit.jupiter.api.Test;

public interface CRUDEndpointTestcases extends EndpointTest {

	@Test
	void testRead() throws Exception;

	@Test
	void testCreate() throws Exception;

	@Test
	void testDelete() throws Exception;

	@Test
	void testUpdate() throws Exception;

	@Test
	void testReadPaged() throws Exception;

	// --- RBAC permission-denied cases ---
	// Every CRUD endpoint test must prove its create/read/list/delete operations are permission-guarded: a
	// caller lacking the mapped permission must be rejected with HTTP 403. AbstractCRUDEndpointTest implements
	// these generically (login as a permissionless user, assert 403) and delegates the entity-specific request
	// to per-test request builders.

	@Test
	void testCreateRequiresPermission() throws Exception;

	@Test
	void testReadRequiresPermission() throws Exception;

	@Test
	void testListRequiresPermission() throws Exception;

	@Test
	void testDeleteRequiresPermission() throws Exception;
}
