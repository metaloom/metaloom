package io.metaloom.loom.agent.memory.dagger;

import java.util.Set;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.agent.memory.tool.DeleteMemoryTool;
import io.metaloom.loom.agent.memory.tool.GetMemoryTool;
import io.metaloom.loom.agent.memory.tool.ListMemoryTool;
import io.metaloom.loom.agent.memory.tool.PutMemoryTool;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.mcp.dagger.MCPTools;
import io.metaloom.loom.mcp.tool.MCPTool;

/**
 * Contributes the memory tools to the MCP tool set.
 *
 * <p>When the memory bank is disabled the set is empty, so the tools are neither registered nor advertised to the model — a disabled feature must not
 * appear in the tool list at all.</p>
 */
@Module
public class MemoryToolModule {

	@ElementsIntoSet
	@Provides
	@MCPTools
	static Set<MCPTool> memoryTools(
		LoomOptions options,
		ListMemoryTool listMemoryTool,
		GetMemoryTool getMemoryTool,
		PutMemoryTool putMemoryTool,
		DeleteMemoryTool deleteMemoryTool) {
		if (!options.getMemory().isEnabled()) {
			return Set.of();
		}
		return Set.of(listMemoryTool, getMemoryTool, putMemoryTool, deleteMemoryTool);
	}

}
