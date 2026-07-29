package io.metaloom.loom.nodes.spec;

/**
 * Derives the {@code llm} node's output ports: one {@code result_<promptId>} per configured prompt.
 *
 * @see PromptPortResolver
 */
public class LlmPortResolver extends PromptPortResolver {

	static final String KIND = "llm";

	@Override
	public String kind() {
		return KIND;
	}

	@Override
	protected String modelLabel() {
		return "the language model";
	}
}
