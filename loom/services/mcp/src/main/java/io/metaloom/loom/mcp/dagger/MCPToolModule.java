package io.metaloom.loom.mcp.dagger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.mcp.tool.impl.AssetStatisticsTool;
import io.metaloom.loom.mcp.tool.impl.GetAssetTool;
import io.metaloom.loom.mcp.tool.impl.GetPipelineTool;
import io.metaloom.loom.mcp.tool.impl.ListCollectionsTool;
import io.metaloom.loom.mcp.tool.impl.ListPipelinesTool;
import io.metaloom.loom.mcp.tool.impl.SearchAssetsTool;
import io.metaloom.loom.mcp.tool.impl.SearchTranscriptTool;

/**
 * Dagger module that provides the initial set of MCP tool implementations.
 */
@Module
public class MCPToolModule {

	@ElementsIntoSet
	@Provides
	@MCPTools
	static Set<MCPTool> mcpTools(
		SearchAssetsTool searchAssetsTool,
		GetAssetTool getAssetTool,
		SearchTranscriptTool searchTranscriptTool,
		ListCollectionsTool listCollectionsTool,
		AssetStatisticsTool assetStatisticsTool,
		ListPipelinesTool listPipelinesTool,
		GetPipelineTool getPipelineTool) {
		return new HashSet<>(Arrays.asList(
			searchAssetsTool,
			getAssetTool,
			searchTranscriptTool,
			listCollectionsTool,
			assetStatisticsTool,
			listPipelinesTool,
			getPipelineTool));
	}

}
