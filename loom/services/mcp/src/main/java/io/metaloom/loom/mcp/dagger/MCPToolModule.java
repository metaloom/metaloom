package io.metaloom.loom.mcp.dagger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.mcp.tool.impl.AssetStatisticsTool;
import io.metaloom.loom.mcp.tool.impl.CreatePipelineTool;
import io.metaloom.loom.mcp.tool.impl.GetAssetTool;
import io.metaloom.loom.mcp.tool.impl.GetNodeDescriptorTool;
import io.metaloom.loom.mcp.tool.impl.GetPipelineTool;
import io.metaloom.loom.mcp.tool.impl.ListCollectionsTool;
import io.metaloom.loom.mcp.tool.impl.ListNodeDescriptorsTool;
import io.metaloom.loom.mcp.tool.impl.ListPipelinesTool;
import io.metaloom.loom.mcp.tool.impl.PipelineAuthoringGuideTool;
import io.metaloom.loom.mcp.tool.impl.SearchAssetsTool;
import io.metaloom.loom.mcp.tool.impl.SearchTranscriptTool;
import io.metaloom.loom.mcp.tool.impl.UpdatePipelineTool;
import io.metaloom.loom.mcp.tool.impl.ValidatePipelineTool;

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
		GetPipelineTool getPipelineTool,
		ListNodeDescriptorsTool listNodeDescriptorsTool,
		GetNodeDescriptorTool getNodeDescriptorTool,
		PipelineAuthoringGuideTool pipelineAuthoringGuideTool,
		ValidatePipelineTool validatePipelineTool,
		CreatePipelineTool createPipelineTool,
		UpdatePipelineTool updatePipelineTool) {
		return new HashSet<>(Arrays.asList(
			searchAssetsTool,
			getAssetTool,
			searchTranscriptTool,
			listCollectionsTool,
			assetStatisticsTool,
			listPipelinesTool,
			getPipelineTool,
			// Pipeline authoring: discover the node vocabulary, learn the format, check a draft, store it.
			listNodeDescriptorsTool,
			getNodeDescriptorTool,
			pipelineAuthoringGuideTool,
			validatePipelineTool,
			createPipelineTool,
			updatePipelineTool));
	}

}
