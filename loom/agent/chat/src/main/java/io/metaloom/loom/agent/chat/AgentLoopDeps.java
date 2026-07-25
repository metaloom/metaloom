package io.metaloom.loom.agent.chat;

import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.agent.sandbox.SandboxOrchestrator;
import io.metaloom.loom.db.model.chat.ChatDao;
import io.metaloom.loom.db.model.chatsession.ChatSessionDao;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.mcp.tool.MCPToolRegistry;

/**
 * The collaborators of one {@link io.metaloom.loom.agent.chat.loop.AgentLoop} run.
 *
 * <p>Grouped into a record so the loop keeps a readable constructor as capabilities are added — a long positional argument list of same-typed DAOs is easy
 * to get subtly wrong at the call site.</p>
 *
 * @param groupDao
 *            Used to resolve the caller's groups for the MCP caller context (shared memory scopes)
 * @param memoryService
 *            The agent memory bank. Never null; it reports itself disabled when the feature is off.
 */
public record AgentLoopDeps(
	ChatDao chatDao,
	ChatSessionDao chatSessionDao,
	SkillDao skillDao,
	GroupDao groupDao,
	MCPToolRegistry toolRegistry,
	SandboxOrchestrator sandbox,
	MemoryService memoryService) {
}
