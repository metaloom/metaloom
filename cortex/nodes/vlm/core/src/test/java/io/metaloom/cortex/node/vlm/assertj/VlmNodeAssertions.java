package io.metaloom.cortex.node.vlm.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.vlm.VlmNodeOptions;

/**
 * Entry point for VLM node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a VLM test
 * needs — it exposes the VLM assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * (generic options, validation results) and AssertJ's own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.vlm.assertj.VlmNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasEndpointUrl("http://127.0.0.1:8000").hasPrompt("olmocr");
 * </pre>
 */
public class VlmNodeAssertions extends NodeAssertions {

	public static VlmNodeOptionsAssert assertThat(VlmNodeOptions actual) {
		return new VlmNodeOptionsAssert(actual);
	}
}
