package io.metaloom.loom.mcp.dagger;

import java.util.Set;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.mcp.attachment.AttachmentTextExtractor;
import io.metaloom.loom.mcp.attachment.PlainTextExtractor;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.mcp.tool.impl.ReadAttachmentTool;

/**
 * Chat attachments: the text extractor, and the {@code read_attachment} tool.
 *
 * <p>
 * Two bindings with deliberately different lifetimes. The extractor is <b>unconditional</b> because
 * MCP {@code resources/read} serves attachments to external clients whether or not the in-chat tool
 * is advertised, and the JSON-RPC handler is always constructed. Only the tool set is gated, the
 * {@code MemoryToolModule} way: a tool description is handed verbatim to the model, so advertising
 * one that cannot work is worse than having none.
 * </p>
 *
 * <p>
 * Swapping in a Tika-backed extractor later is a change to the one {@code @Provides} below and
 * nothing else. See {@link AttachmentTextExtractor} for why that is not today's change.
 * </p>
 */
@Module
public class ChatAttachmentToolModule {

	@Provides
	@Singleton
	static AttachmentTextExtractor attachmentTextExtractor(PlainTextExtractor plainTextExtractor) {
		return plainTextExtractor;
	}

	@ElementsIntoSet
	@Provides
	@MCPTools
	static Set<MCPTool> chatAttachmentTools(LoomOptions options, ReadAttachmentTool readAttachmentTool) {
		if (!options.getChatAttachment().isEnabled()) {
			return Set.of();
		}
		return Set.of(readAttachmentTool);
	}
}
