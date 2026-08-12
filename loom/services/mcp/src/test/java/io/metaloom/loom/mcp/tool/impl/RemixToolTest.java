package io.metaloom.loom.mcp.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.remix.Remix;
import io.metaloom.loom.db.model.remix.RemixDao;
import io.metaloom.loom.db.model.remix.RemixMember;
import io.metaloom.loom.db.model.remix.RemixRole;
import io.metaloom.loom.db.page.Page;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The remix MCP tools, against mocked DAOs.
 *
 * <p>
 * Nothing asserts the total number of registered tools, so a per-tool test is the only thing that
 * notices when one of these stops working.
 * </p>
 */
public class RemixToolTest {

	private static final UUID REMIX_UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
	private static final UUID SOURCE_ASSET = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
	private static final UUID DERIVED_ASSET = UUID.fromString("dddddddd-0000-0000-0000-000000000002");

	private DaoCollection daos;
	private RemixDao remixDao;

	@BeforeEach
	public void setup() {
		daos = mock(DaoCollection.class);
		remixDao = mock(RemixDao.class);
		when(daos.remixDao()).thenReturn(remixDao);

		Remix remix = mock(Remix.class);
		when(remix.getUuid()).thenReturn(REMIX_UUID);
		when(remix.getName()).thenReturn("Coastal drone cuts");
		when(remix.getDescription()).thenReturn("The original and the cuts made from it.");
		when(remix.getSourceAssetUuid()).thenReturn(SOURCE_ASSET);

		// Built before the stubbing below: member() stubs its own mock, and doing that inside a
		// thenReturn(...) argument leaves Mockito with an unfinished when() and fails every test here.
		List<RemixMember> members = List.of(
			member(SOURCE_ASSET, RemixRole.SOURCE, "drone-coastal.mp4"),
			member(DERIVED_ASSET, RemixRole.DERIVED, "coastal-cut.mp4"));

		when(remixDao.load(REMIX_UUID)).thenReturn(remix);
		when(remixDao.countAssets(REMIX_UUID)).thenReturn(2L);
		when(remixDao.loadPage(any(), anyInt(), any(), any(), any())).thenReturn(new Page<>(25, 1, List.of(remix)));
		when(remixDao.loadMembers(any(), any(), anyInt())).thenReturn(new Page<>(25, 2, members));
	}

	private RemixMember member(UUID assetUuid, RemixRole role, String filename) {
		RemixMember member = mock(RemixMember.class);
		when(member.getUuid()).thenReturn(UUID.randomUUID());
		when(member.getAssetUuid()).thenReturn(assetUuid);
		when(member.getRole()).thenReturn(role);
		when(member.getFilename()).thenReturn(filename);
		when(member.getMimeType()).thenReturn("video/mp4");
		when(member.getSize()).thenReturn(4711L);
		return member;
	}

	private static String text(JsonObject result) {
		JsonArray content = result.getJsonArray("content");
		assertNotNull(content, "The tool result should carry content");
		return content.getJsonObject(0).getString("text");
	}

	@Test
	public void testListRemixesDescriptor() {
		ListRemixesTool tool = new ListRemixesTool(daos);
		assertEquals("list_remixes", tool.descriptor().name());
		assertEquals(List.of("READ_REMIX"), tool.descriptor().requiredPermissions());
	}

	@Test
	public void testListRemixes() {
		ListRemixesTool tool = new ListRemixesTool(daos);
		JsonObject result = tool.execute(new JsonObject()).result();

		String body = text(result);
		assertTrue(body.contains("Coastal drone cuts"), "The remix name should be in the text the model sees");
		assertTrue(body.contains("\"memberCount\" : 2"), "The member count should be reported");
	}

	/**
	 * Reading a remix's members exposes asset filenames, so the tool demands READ_ASSET on top of
	 * READ_REMIX - the same rule the REST route enforces. Dropping it here would make the tool a
	 * side channel around asset visibility.
	 */
	@Test
	public void testGetRemixRequiresAssetPermissionToo() {
		GetRemixTool tool = new GetRemixTool(daos);
		assertEquals(List.of("READ_REMIX", "READ_ASSET"), tool.descriptor().requiredPermissions());
	}

	@Test
	public void testGetRemix() {
		GetRemixTool tool = new GetRemixTool(daos);
		JsonObject result = tool.execute(new JsonObject().put("remixUuid", REMIX_UUID.toString())).result();

		String body = text(result);
		assertTrue(body.contains("drone-coastal.mp4"), "Members should be listed with their filenames");
		assertTrue(body.contains("SOURCE"), "The source member's role should be visible");
		assertTrue(body.contains("DERIVED"), "So should a derived member's");
	}

	/** Every member becomes a reference, so the chat UI can render an asset card per member. */
	@Test
	public void testGetRemixEmitsAReferencePerMember() {
		GetRemixTool tool = new GetRemixTool(daos);
		JsonObject result = tool.execute(new JsonObject().put("remixUuid", REMIX_UUID.toString())).result();

		JsonArray references = result.getJsonArray("references");
		assertNotNull(references, "The result should carry references");
		assertEquals(3, references.size(), "One reference for the remix plus one per member");
	}

	@Test
	public void testGetRemixWithAnUnknownUuid() {
		when(remixDao.load(any())).thenReturn(null);
		GetRemixTool tool = new GetRemixTool(daos);

		UUID unknown = UUID.fromString("cccccccc-0000-0000-0000-0000000000ff");
		assertTrue(text(tool.execute(new JsonObject().put("remixUuid", unknown.toString())).result())
			.contains("Remix not found"));
	}

	/** A malformed uuid answers rather than throwing: the model should be able to correct itself. */
	@Test
	public void testGetRemixWithAMalformedUuid() {
		GetRemixTool tool = new GetRemixTool(daos);
		assertTrue(text(tool.execute(new JsonObject().put("remixUuid", "not-a-uuid")).result()).contains("Not a uuid"));
	}

	@Test
	public void testGetRemixWithoutTheParameter() {
		GetRemixTool tool = new GetRemixTool(daos);
		assertTrue(tool.execute(new JsonObject()).failed(), "A missing remixUuid should fail the call");
	}

}
