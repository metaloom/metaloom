package io.metaloom.cortex.node.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class FingerprintDedupDiscoverOptionsValidationTest {

	@Test
	public void testDefaultsValid() {
		assertThat(new FingerprintDedupDiscoverOptions().validate().isValid()).isTrue();
	}

	@Test
	public void testBlankAlgorithmInvalid() {
		ValidationResult result = new FingerprintDedupDiscoverOptions().setAlgorithm("  ").validate();
		assertThat(result.isValid()).isFalse();
		assertThat(result.getErrors()).anyMatch(e -> e.contains("algorithm"));
	}

	@Test
	public void testNonPositiveTopKInvalid() {
		ValidationResult result = new FingerprintDedupDiscoverOptions().setTopK(0).validate();
		assertThat(result.isValid()).isFalse();
		assertThat(result.getErrors()).anyMatch(e -> e.contains("topK"));
	}

	@Test
	public void testNegativeThresholdInvalid() {
		ValidationResult result = new FingerprintDedupDiscoverOptions().setScoreThreshold(-0.5f).validate();
		assertThat(result.isValid()).isFalse();
		assertThat(result.getErrors()).anyMatch(e -> e.contains("scoreThreshold"));
	}
}
