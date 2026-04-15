package io.metaloom.cortex.pipeline.test.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.pipeline.api.PipelineResult;

/**
 * Entry point for pipeline-related AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
 *
 * assertThat(result).isSuccess().hasCompletedNode("md5");
 * assertThat(result).node("md5").hasOutput("md5");
 * </pre>
 */
public class PipelineAssertions extends Assertions {

	public static PipelineResultAssert assertThat(PipelineResult actual) {
		return new PipelineResultAssert(actual);
	}
}
