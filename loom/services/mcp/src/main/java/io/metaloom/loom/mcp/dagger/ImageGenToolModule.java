package io.metaloom.loom.mcp.dagger;

import java.util.Set;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.mcp.tool.impl.GenerateImageTool;

/**
 * Contributes the {@code generate_image} tool to the MCP tool set.
 *
 * <p>
 * It lives in its own module rather than in {@code MCPToolModule} because it is feature-gated, and
 * {@code MCPToolModule} provides its twenty-odd tools as one unconditional set. When image
 * generation is disabled the set here is empty, so the tool is neither registered nor advertised —
 * the {@code MemoryToolModule} pattern, and the rule it exists for: a tool description is handed
 * verbatim to the model, so an advertised tool that can only fail is worse than an absent one.
 * </p>
 *
 * <p>
 * It is off by default because it needs an image-generation sidecar on the other end. See
 * {@code ImageGenToolOptions}.
 * </p>
 */
@Module
public class ImageGenToolModule {

	@ElementsIntoSet
	@Provides
	@MCPTools
	static Set<MCPTool> imageGenTools(LoomOptions options, GenerateImageTool generateImageTool) {
		if (!options.getImageGenTool().isEnabled()) {
			return Set.of();
		}
		return Set.of(generateImageTool);
	}
}
