package io.metaloom.loom.db.model.chatsession;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

/**
 * A durable, publishable record behind one chat: its AI-generated (and user-editable) name and
 * description, tags, publish flag and a snapshot of the coding sandbox filesystem ({@code /session}).
 *
 * <p>The active skill versions ({@link ChatSessionSkillPin}) and the context references to other
 * published sessions ({@link ChatSessionContextRef}) are managed via the {@link ChatSessionDao} and
 * live in their own join tables.</p>
 */
public interface ChatSession extends CUDElement<ChatSession>, MetaElement<ChatSession> {

	/**
	 * The owning chat. May be {@code null} once the chat has been deleted — a published session
	 * survives its chat.
	 */
	UUID getChatUuid();

	ChatSession setChatUuid(UUID chatUuid);

	String getName();

	ChatSession setName(String name);

	String getDescription();

	ChatSession setDescription(String description);

	String[] getTags();

	ChatSession setTags(String[] tags);

	boolean isPublished();

	ChatSession setPublished(boolean published);

	/**
	 * Asset pool holding the {@code /session} filesystem tarball ({@code null} until first snapshot).
	 */
	UUID getPoolUuid();

	ChatSession setPoolUuid(UUID poolUuid);

	String getBlobPath();

	ChatSession setBlobPath(String blobPath);

	Long getFsSize();

	ChatSession setFsSize(Long fsSize);

	String getFsSha256();

	ChatSession setFsSha256(String fsSha256);

}
