package io.metaloom.loom.mcp.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.ChatAttachmentOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.mcp.attachment.ChatAttachmentReader;
import io.metaloom.loom.mcp.attachment.ChatAttachmentReader.TextSlice;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@code read_attachment}.
 *
 * <p>
 * Two things are being pinned. The first is the security boundary: which chat a caller is allowed to
 * read from is resolved on the server, and a chat named in the arguments may only narrow that, never
 * widen it. The second is the error philosophy the whole MCP surface follows — an unknown id, a
 * picture or a PDF is a <em>sentence</em>, not a failed future, because a failed future ends the turn
 * and tells the user nothing.
 * </p>
 */
public class ReadAttachmentToolTest {

	private static final UUID USER_UUID = UUID.randomUUID();
	private static final UUID CHAT_UUID = UUID.randomUUID();
	private static final UUID ATTACHMENT_UUID = UUID.randomUUID();

	private ChatAttachmentReader reader;
	private LoomOptions loomOptions;

	/** The in-chat caller: the server put the chat uuid on the context. */
	private final MCPCallerContext caller = new MCPCallerContext(USER_UUID, "tester", Set.of(), null, CHAT_UUID);

	@BeforeEach
	public void setup() {
		reader = mock(ChatAttachmentReader.class);
		loomOptions = new LoomOptions();
		loomOptions.setChatAttachment(new ChatAttachmentOptions().setMaxReadChars(100));
	}

	private ReadAttachmentTool tool() {
		return new ReadAttachmentTool(reader, loomOptions);
	}

	private Attachment attachment(String filename, String mimeType) {
		Attachment attachment = mock(Attachment.class);
		when(attachment.getUuid()).thenReturn(ATTACHMENT_UUID);
		when(attachment.getFilename()).thenReturn(filename);
		when(attachment.getMimeType()).thenReturn(mimeType);
		return attachment;
	}

	private static String text(JsonObject result) {
		JsonArray content = result.getJsonArray("content");
		return content.getJsonObject(0).getString("text");
	}

	// ---- descriptor ------------------------------------------------------------------------

	@Test
	public void testDescriptor() throws Exception {
		MCPToolDescriptor descriptor = tool().descriptor();

		assertEquals("read_attachment", descriptor.name());
		assertEquals(List.of("READ_CHAT", "READ_ATTACHMENT"), descriptor.requiredPermissions());
		// Without identity there is no chat to scope to, so this must never be dispatchable anonymously.
		assertTrue(descriptor.requiresIdentity());

		JsonObject schema = descriptor.inputSchema();
		assertEquals(List.of("attachmentId"), schema.getJsonArray("required").getList());
		JsonObject properties = schema.getJsonObject("properties");
		assertEquals("string", properties.getJsonObject("attachmentId").getString("type"));
		assertEquals("integer", properties.getJsonObject("offset").getString("type"));
		assertEquals("integer", properties.getJsonObject("limit").getString("type"));
	}

	@Test
	public void testTheArgumentOnlyOverloadCannotRun() throws Exception {
		// The registry gives identity tools no EventBus address; this is the second lock on the door.
		assertTrue(tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString())).failed());
	}

	@Test
	public void testUnauthenticatedCallersAreRefused() throws Exception {
		Future<JsonObject> result = tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()),
			MCPCallerContext.ANONYMOUS);

		assertTrue(result.failed());
	}

	// ---- scoping ---------------------------------------------------------------------------

	@Test
	public void testTheChatComesFromTheContextNotTheArguments() throws Exception {
		Attachment brief = attachment("brief.md", "text/markdown");
		when(reader.resolve(eq(ATTACHMENT_UUID), eq(CHAT_UUID), eq(USER_UUID))).thenReturn(brief);
		when(reader.isReadableAsText(brief)).thenReturn(true);
		when(reader.exists(brief)).thenReturn(true);
		when(reader.text(eq(brief), eq(0), eq(100))).thenReturn(new TextSlice("hello", 0, 5, 5));

		// A chat uuid in the arguments must be ignored while the context has one, or a model could
		// read out of a different conversation by naming it.
		UUID otherChat = UUID.randomUUID();
		JsonObject args = new JsonObject()
			.put("attachmentId", ATTACHMENT_UUID.toString())
			.put("chatUuid", otherChat.toString());

		tool().execute(args, caller).result();

		verify(reader).resolve(ATTACHMENT_UUID, CHAT_UUID, USER_UUID);
		verify(reader, never()).resolve(ATTACHMENT_UUID, otherChat, USER_UUID);
	}

	@Test
	public void testAClientOutsideAChatMayNameOne() throws Exception {
		// An external MCP client has no chat on its context. It may name one, and ChatAttachmentReader
		// then re-checks that the caller owns it - so the argument narrows and cannot widen.
		MCPCallerContext external = new MCPCallerContext(USER_UUID, "tester", Set.of(), null, null);
		Attachment brief = attachment("brief.md", "text/markdown");
		when(reader.resolve(eq(ATTACHMENT_UUID), eq(CHAT_UUID), eq(USER_UUID))).thenReturn(brief);
		when(reader.isReadableAsText(brief)).thenReturn(true);
		when(reader.exists(brief)).thenReturn(true);
		when(reader.text(any(), anyInt(), anyInt())).thenReturn(new TextSlice("hello", 0, 5, 5));

		JsonObject args = new JsonObject()
			.put("attachmentId", ATTACHMENT_UUID.toString())
			.put("chatUuid", CHAT_UUID.toString());

		JsonObject result = tool().execute(args, external).result();

		assertTrue(text(result).contains("hello"), text(result));
		verify(reader).resolve(ATTACHMENT_UUID, CHAT_UUID, USER_UUID);
	}

	@Test
	public void testAnAttachmentTheCallerMayNotSeeIsReportedAsAbsent() throws Exception {
		// Not "forbidden": saying so would confirm that a guessed uuid names a real file.
		when(reader.resolve(any(), any(), any())).thenReturn(null);

		JsonObject result = tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()), caller).result();

		assertTrue(text(result).contains("There is no attachment"), text(result));
		assertFalse(text(result).toLowerCase().contains("permission"), text(result));
	}

	@Test
	public void testAMalformedIdIsAnAnswerNotAFailure() throws Exception {
		Future<JsonObject> result = tool().execute(new JsonObject().put("attachmentId", "not-a-uuid"), caller);

		assertTrue(result.succeeded());
		assertTrue(text(result.result()).contains("not one of the attachment ids"), text(result.result()));
	}

	// ---- reading ---------------------------------------------------------------------------

	@Test
	public void testTextIsReturnedWithTheFilename() throws Exception {
		Attachment brief = attachment("brief.md", "text/markdown");
		when(reader.resolve(any(), any(), any())).thenReturn(brief);
		when(reader.isReadableAsText(brief)).thenReturn(true);
		when(reader.exists(brief)).thenReturn(true);
		when(reader.text(eq(brief), eq(0), eq(100))).thenReturn(new TextSlice("the whole brief", 0, 15, 15));

		JsonObject result = tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()), caller).result();

		assertTrue(text(result).contains("brief.md"), text(result));
		assertTrue(text(result).contains("the whole brief"), text(result));
		// Nothing was cut, so there must be no truncation notice to confuse the model into paging.
		assertFalse(text(result).contains("Showing characters"), text(result));
	}

	@Test
	public void testATruncatedReadSaysWhereToContinueFrom() throws Exception {
		Attachment brief = attachment("long.txt", "text/plain");
		when(reader.resolve(any(), any(), any())).thenReturn(brief);
		when(reader.isReadableAsText(brief)).thenReturn(true);
		when(reader.exists(brief)).thenReturn(true);
		when(reader.text(eq(brief), eq(0), eq(100))).thenReturn(new TextSlice("first hundred", 0, 100, 5000));

		JsonObject result = tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()), caller).result();

		assertTrue(text(result).contains("Showing characters 0-100 of 5000"), text(result));
		// The next call has to write itself, or the model re-reads the same window.
		assertTrue(text(result).contains("offset 100"), text(result));
	}

	@Test
	public void testTheLastWindowSaysItIsTheEnd() throws Exception {
		Attachment brief = attachment("long.txt", "text/plain");
		when(reader.resolve(any(), any(), any())).thenReturn(brief);
		when(reader.isReadableAsText(brief)).thenReturn(true);
		when(reader.exists(brief)).thenReturn(true);
		when(reader.text(eq(brief), eq(4900), eq(100))).thenReturn(new TextSlice("tail", 4900, 5000, 5000));

		JsonObject args = new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()).put("offset", 4900);
		JsonObject result = tool().execute(args, caller).result();

		assertTrue(text(result).contains("That is the end of the file"), text(result));
	}

	@Test
	public void testTheConfiguredLimitAppliesWhenTheModelNamesNone() throws Exception {
		Attachment brief = attachment("long.txt", "text/plain");
		when(reader.resolve(any(), any(), any())).thenReturn(brief);
		when(reader.isReadableAsText(brief)).thenReturn(true);
		when(reader.exists(brief)).thenReturn(true);
		when(reader.text(any(), anyInt(), anyInt())).thenReturn(new TextSlice("x", 0, 1, 1));

		tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()), caller).result();

		verify(reader).text(brief, 0, 100);
	}

	@Test
	public void testAnImageIsRedirectedToTheImageTool() throws Exception {
		Attachment hero = attachment("hero.jpg", "image/jpeg");
		when(reader.resolve(any(), any(), any())).thenReturn(hero);
		when(reader.isReadableAsText(hero)).thenReturn(false);

		JsonObject result = tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()), caller).result();

		// Not an apology that it cannot see pictures: the id and the tool that can use it.
		assertTrue(text(result).contains("generate_image"), text(result));
		assertTrue(text(result).contains(ATTACHMENT_UUID.toString()), text(result));
	}

	@Test
	public void testAnUnreadableTypeNamesTheType() throws Exception {
		Attachment contract = attachment("contract.pdf", "application/pdf");
		when(reader.resolve(any(), any(), any())).thenReturn(contract);
		when(reader.isReadableAsText(contract)).thenReturn(false);

		JsonObject result = tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()), caller).result();

		// The user is told what is missing rather than that "it did not work".
		assertTrue(text(result).contains("application/pdf"), text(result));
		assertTrue(text(result).contains("not available in this deployment"), text(result));
	}

	@Test
	public void testMissingBytesAreAnAnswerNotAFailure() throws Exception {
		Attachment brief = attachment("brief.md", "text/markdown");
		when(reader.resolve(any(), any(), any())).thenReturn(brief);
		when(reader.isReadableAsText(brief)).thenReturn(true);
		when(reader.exists(brief)).thenReturn(false);

		Future<JsonObject> result = tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()), caller);

		assertTrue(result.succeeded());
		assertTrue(text(result.result()).contains("missing"), text(result.result()));
	}

	@Test
	public void testTheResultReferencesTheAttachment() throws Exception {
		Attachment brief = attachment("brief.md", "text/markdown");
		when(reader.resolve(any(), any(), any())).thenReturn(brief);
		when(reader.isReadableAsText(brief)).thenReturn(true);
		when(reader.exists(brief)).thenReturn(true);
		when(reader.text(any(), anyInt(), anyInt())).thenReturn(new TextSlice("hi", 0, 2, 2));

		JsonObject result = tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()), caller).result();

		JsonObject reference = result.getJsonArray("references").getJsonObject(0);
		assertEquals("attachment", reference.getString("type"));
		assertEquals(ATTACHMENT_UUID.toString(), reference.getString("uuid"));
	}

	@Test
	public void testAReaderFailureIsReportedAsText() throws Exception {
		Attachment brief = attachment("brief.md", "text/markdown");
		when(reader.resolve(any(), any(), any())).thenReturn(brief);
		when(reader.isReadableAsText(brief)).thenReturn(true);
		when(reader.exists(brief)).thenReturn(true);
		when(reader.text(any(), anyInt(), anyInt())).thenThrow(new RuntimeException("storage is down"));

		Future<JsonObject> result = tool().execute(new JsonObject().put("attachmentId", ATTACHMENT_UUID.toString()), caller);

		assertTrue(result.succeeded());
		assertTrue(text(result.result()).contains("storage is down"), text(result.result()));
	}

}
