package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.CREATE_ATTACHMENT;
import static io.metaloom.loom.db.model.perm.Permission.CREATE_CHAT;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ATTACHMENT;
import static io.metaloom.loom.db.model.perm.Permission.READ_ATTACHMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentListResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentResponse;
import io.metaloom.loom.rest.model.chat.ChatCreateRequest;
import io.metaloom.loom.rest.model.chat.ChatResponse;

/**
 * {@code /chats/:uuid/attachments} — files dropped into a conversation.
 *
 * <p>
 * The cases here divide into three. <b>Lifecycle</b>: a file can be attached, listed, downloaded and
 * detached. <b>Ownership</b>: every route is a 404 for somebody else's chat, because a conversation
 * is private and no permission in the ACL can express "your own conversations". <b>Permissions</b>:
 * each route needs its own, granted through a role and a group, never a direct user grant.
 * </p>
 */
public class ChatAttachmentEndpointTest extends AbstractEndpointTest {

	@TempDir
	Path tmp;

	private File textFile(String name, String content) throws Exception {
		Path path = tmp.resolve(name);
		Files.writeString(path, content, StandardCharsets.UTF_8);
		return path.toFile();
	}

	private ChatResponse createChat(LoomHttpClient client, String title) throws Exception {
		ChatCreateRequest request = new ChatCreateRequest();
		request.setTitle(title);
		return client.createChat(request).sync().body();
	}

	private AttachmentResponse attach(LoomHttpClient client, UUID chatUuid, String name, String content) throws Exception {
		return client.uploadChatAttachment(chatUuid, textFile(name, content), "text/markdown").sync().body();
	}

	// ---- lifecycle ---------------------------------------------------------------------------

	@Test
	public void testAttachListAndDetach() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			ChatResponse chat = createChat(client, "with-files");

			AttachmentResponse attached = attach(client, chat.getUuid(), "brief.md", "the brief");
			assertNotNull(attached.getUuid());
			assertEquals("brief.md", attached.getFilename());
			assertEquals("text/markdown", attached.getMimeType());

			AttachmentListResponse list = client.listChatAttachments(chat.getUuid()).sync().body();
			assertEquals(1, list.getData().size());
			assertEquals(attached.getUuid(), list.getData().get(0).getUuid());

			client.deleteChatAttachment(chat.getUuid(), attached.getUuid()).sync().body();

			assertTrue(client.listChatAttachments(chat.getUuid()).sync().body().getData().isEmpty());
		}
	}

	@Test
	public void testTheBytesComeBack() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			ChatResponse chat = createChat(client, "with-files");
			AttachmentResponse attached = attach(client, chat.getUuid(), "brief.md", "the whole brief");

			byte[] bytes = client.downloadChatAttachment(chat.getUuid(), attached.getUuid()).sync().body().getStream().readAllBytes();

			assertEquals("the whole brief", new String(bytes, StandardCharsets.UTF_8));
		}
	}

	@Test
	public void testAnAttachmentOfAnotherChatIsNotReachableThroughThisOne() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			ChatResponse first = createChat(client, "first");
			ChatResponse second = createChat(client, "second");
			AttachmentResponse inFirst = attach(client, first.getUuid(), "brief.md", "x");

			// Both chats are the caller's, so ownership alone does not cover this: the attachment has
			// to be checked against the chat named in the path.
			expect(404, "Not Found", client.downloadChatAttachment(second.getUuid(), inFirst.getUuid()));
			expect(404, "Not Found", client.deleteChatAttachment(second.getUuid(), inFirst.getUuid()));
		}
	}

	@Test
	public void testAnUnknownChatIsNotFound() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.listChatAttachments(UUID.randomUUID()));
		}
	}

	// ---- promotion ---------------------------------------------------------------------------

	@Test
	public void testSavingToTheLibraryCreatesAnAssetAndKeepsTheAttachment() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			ChatResponse chat = createChat(client, "with-files");
			AttachmentResponse attached = attach(client, chat.getUuid(), "keeper.md", "worth keeping");
			UUID libraryUuid = daos().libraryDao().findAll().iterator().next().getUuid();

			AssetResponse asset = client.saveChatAttachmentToLibrary(chat.getUuid(), attached.getUuid(), libraryUuid).sync().body();

			assertNotNull(asset.getUuid());
			// A file can be in the conversation and in the library at once. Moving it would make
			// "save this" silently remove it from the chat the user is still talking about.
			assertEquals(1, client.listChatAttachments(chat.getUuid()).sync().body().getData().size());
		}
	}

	@Test
	public void testSavingWithoutALibraryIsARefusalThatSaysWhatToDo() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			ChatResponse chat = createChat(client, "with-files");
			AttachmentResponse attached = attach(client, chat.getUuid(), "keeper.md", "x");

			// LOOM_CHAT_ATTACHMENT_LIBRARY is unset in the test deployment, so there is no default to
			// fall back to. A 400 naming both ways out beats guessing a library for the user.
			expect(400, "Bad Request", client.saveChatAttachmentToLibrary(chat.getUuid(), attached.getUuid(), null));
		}
	}

	// ---- ownership ---------------------------------------------------------------------------

	@Test
	public void testAForeignChatIsIndistinguishableFromAMissingOne() throws Exception {
		ChatResponse adminChat;
		AttachmentResponse adminAttachment;
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			adminChat = createChat(client, "admin-owned");
			adminAttachment = attach(client, adminChat.getUuid(), "private.md", "secret");
		}

		// Holds every attachment permission there is, and still must not see another user's chat:
		// permissions cannot express "your own conversations".
		try (LoomHttpClient client = loginClientWith("chat-attachment-outsider",
			CREATE_ATTACHMENT, READ_ATTACHMENT, DELETE_ATTACHMENT, CREATE_CHAT, CREATE_ASSET)) {
			expect(404, "Not Found", client.listChatAttachments(adminChat.getUuid()));
			expect(404, "Not Found", client.downloadChatAttachment(adminChat.getUuid(), adminAttachment.getUuid()));
			expect(404, "Not Found", client.deleteChatAttachment(adminChat.getUuid(), adminAttachment.getUuid()));
			expect(404, "Not Found", client.uploadChatAttachment(adminChat.getUuid(), textFile("intruder.md", "x"), "text/markdown"));
		}

		// And it is still there for its owner.
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			assertEquals(1, client.listChatAttachments(adminChat.getUuid()).sync().body().getData().size());
		}
	}

	/**
	 * The privacy guard on the shared table.
	 *
	 * <p>
	 * {@code CHAT_FILE} rows live on the same {@code attachment} table as thumbnails and face crops,
	 * which have a generic listing gated on {@code READ_ATTACHMENT} alone. Without this guard anyone
	 * holding that permission could enumerate and download every file every user had ever dropped
	 * into a private conversation.
	 * </p>
	 */
	@Test
	public void testChatFilesAreHiddenFromTheGenericAttachmentRoutes() throws Exception {
		ChatResponse adminChat;
		AttachmentResponse adminAttachment;
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			adminChat = createChat(client, "admin-owned");
			adminAttachment = attach(client, adminChat.getUuid(), "private.md", "secret");
		}

		try (LoomHttpClient client = loginClientWith("generic-attachment-reader", READ_ATTACHMENT, DELETE_ATTACHMENT)) {
			expect(404, "Not Found", client.loadAttachment(adminAttachment.getUuid()));
			expect(404, "Not Found", client.downloadAttachment(adminAttachment.getUuid()));
			expect(404, "Not Found", client.deleteAttachment(adminAttachment.getUuid()));

			AttachmentListResponse generic = client.listAttachments().sync().body();
			if (generic.getData() != null) {
				assertTrue(generic.getData().stream().noneMatch(a -> a.getUuid().equals(adminAttachment.getUuid())),
					"the generic listing must not contain chat files");
			}
		}
	}

	@Test
	public void testAChatFileCannotBeCreatedThroughTheGenericRoute() throws Exception {
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			// That route has no chat to attach it to, and a CHAT_FILE with a null chat_uuid is an
			// orphan no ownership check can ever admit and no cascade can ever clean up.
			expect(400, "Bad Request",
				client.uploadAttachment(textFile("sneaky.md", "x"), "text/markdown", null, "CHAT_FILE"));
		}
	}

	// ---- permissions -------------------------------------------------------------------------

	@Test
	public void testUploadRequiresCreateAttachment() throws Exception {
		ChatResponse chat = ownChatOf("upload-perm-user", CREATE_CHAT, READ_ATTACHMENT);
		try (LoomHttpClient client = loginAs("upload-perm-user")) {
			expect(403, "Forbidden", client.uploadChatAttachment(chat.getUuid(), textFile("a.md", "x"), "text/markdown"));
		}
	}

	@Test
	public void testListRequiresReadAttachment() throws Exception {
		ChatResponse chat = ownChatOf("list-perm-user", CREATE_CHAT, CREATE_ATTACHMENT);
		try (LoomHttpClient client = loginAs("list-perm-user")) {
			expect(403, "Forbidden", client.listChatAttachments(chat.getUuid()));
		}
	}

	@Test
	public void testDeleteRequiresDeleteAttachment() throws Exception {
		ChatResponse chat = ownChatOf("delete-perm-user", CREATE_CHAT, CREATE_ATTACHMENT, READ_ATTACHMENT);
		try (LoomHttpClient client = loginAs("delete-perm-user")) {
			AttachmentResponse attached = attach(client, chat.getUuid(), "a.md", "x");
			expect(403, "Forbidden", client.deleteChatAttachment(chat.getUuid(), attached.getUuid()));
		}
	}

	@Test
	public void testSavingToTheLibraryRequiresCreateAsset() throws Exception {
		// Deliberately an all-or-nothing pair: READ_ATTACHMENT alone reads the file, CREATE_ASSET
		// alone cannot reach it, and only both together may put it in the library.
		ChatResponse chat = ownChatOf("promote-perm-user", CREATE_CHAT, CREATE_ATTACHMENT, READ_ATTACHMENT);
		try (LoomHttpClient client = loginAs("promote-perm-user")) {
			AttachmentResponse attached = attach(client, chat.getUuid(), "a.md", "x");
			UUID libraryUuid = daos().libraryDao().findAll().iterator().next().getUuid();
			expect(403, "Forbidden", client.saveChatAttachmentToLibrary(chat.getUuid(), attached.getUuid(), libraryUuid));
		}
	}

	@Test
	public void testAPermissionlessCallerReachesNothing() throws Exception {
		ChatResponse chat;
		try (LoomHttpClient client = httpClient()) {
			loginAdmin(client);
			chat = createChat(client, "admin-owned");
		}
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.listChatAttachments(chat.getUuid()));
			expect(403, "Forbidden", client.uploadChatAttachment(chat.getUuid(), textFile("a.md", "x"), "text/markdown"));
		}
	}

	/**
	 * Provision a user with the given permissions and leave them owning a chat.
	 *
	 * <p>
	 * The permission cases need a chat the caller owns, or the ownership 404 fires before the
	 * permission check they are actually about and they pass for the wrong reason.
	 * </p>
	 */
	private ChatResponse ownChatOf(String username, io.metaloom.loom.db.model.perm.Permission... permissions) throws Exception {
		try (LoomHttpClient client = loginClientWith(username, permissions)) {
			return createChat(client, username + "-chat");
		}
	}

	private LoomHttpClient loginAs(String username) throws Exception {
		LoomHttpClient client = httpClient();
		client.setToken(client.login(username, "secret").sync().body().getToken());
		return client;
	}

}
