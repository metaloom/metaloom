package io.metaloom.loom.nodes.spec;

/**
 * Derives the {@code vlm} node's output ports: one {@code result_<promptId>} per configured prompt.
 *
 * <p>
 * A {@code vlm} node left without prompts falls back to the olmOCR preset at runtime, which is
 * exactly the case the inherited single {@code result} port covers.
 * </p>
 *
 * @see PromptPortResolver
 */
public class VlmPortResolver extends PromptPortResolver {

	static final String KIND = "vlm";

	@Override
	public String kind() {
		return KIND;
	}

	@Override
	protected String modelLabel() {
		return "the vision-language model";
	}
}
