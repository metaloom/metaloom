package io.metaloom.loom.rest.service.impl;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.chat.Chat;

/**
 * "This chat is yours" — the one rule every chat sub-resource has to enforce, in one place.
 *
 * <p>
 * Permissions decide what a caller may do with chats in general; they cannot express <em>whose</em>
 * chat, and a conversation is private. So the stream route, the session filesystem and the
 * attachment routes all check ownership on top of their permission, and they must all check it the
 * same way.
 * </p>
 *
 * <p>
 * It was written out by hand in {@code ChatStreamEndpointService} and {@code SessionFsEndpointService}
 * before the attachment routes needed it a third time, which is where the multipart helpers on
 * {@code AbstractEndpointService} were extracted from too, for the same reason: three copies of a
 * security check is three chances to leave one out.
 * </p>
 *
 * <h2>404, not 403</h2>
 *
 * <p>
 * A chat belonging to somebody else answers exactly as a chat that does not exist. 403 would confirm
 * that a uuid is real and that somebody is using it, which is a small leak but a free one to avoid —
 * a caller who is not the owner has no legitimate way to tell the difference apart.
 * </p>
 */
@Singleton
public class ChatOwnership {

	private final DaoCollection daos;

	@Inject
	public ChatOwnership(DaoCollection daos) {
		this.daos = daos;
	}

	/**
	 * Load a chat, insisting the given user created it.
	 *
	 * @throws LoomRestException 404 when the chat does not exist or belongs to somebody else
	 */
	public Chat loadOwned(UUID chatUuid, UUID userUuid) {
		Chat chat = daos.chatDao().load(chatUuid);
		// Foreign chats must be indistinguishable from missing ones
		if (chat == null || chat.getCreatorUuid() == null || !chat.getCreatorUuid().equals(userUuid)) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
		}
		return chat;
	}

	/**
	 * Whether the given user created the given chat, without throwing.
	 *
	 * <p>
	 * For callers that are filtering rather than fetching — the generic attachment routes hide chat
	 * files that are not the caller's rather than failing the whole listing.
	 * </p>
	 */
	public boolean isOwnedBy(UUID chatUuid, UUID userUuid) {
		if (chatUuid == null || userUuid == null) {
			return false;
		}
		Chat chat = daos.chatDao().load(chatUuid);
		return chat != null && userUuid.equals(chat.getCreatorUuid());
	}

}
