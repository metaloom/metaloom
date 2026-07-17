package io.metaloom.cortex.node.dedup;

import static io.metaloom.cortex.api.option.assertj.OptionsAssertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class DedupNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		DedupNodeOptions options = new DedupNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testCustomDupFolderValid() {
		DedupNodeOptions options = new DedupNodeOptions();
		options.setDupFolder(Paths.get("custom/duplicates"));
		io.metaloom.cortex.node.dedup.assertj.DedupNodeAssertions.assertThat(options)
			.isValid().hasDupFolder(Paths.get("custom/duplicates"));
	}

	@Test
	public void testNullDupFolderInvalid() {
		DedupNodeOptions options = new DedupNodeOptions();
		options.setDupFolder(null);
		assertThat(options).isInvalid().hasErrorCount(1).hasError("dupFolder must not be null");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		DedupNodeOptions options = new DedupNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		DedupNodeOptions options = new DedupNodeOptions();
		options.setDupFolder(null);
		
		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("dupFolder must not be null");
		
		DedupNodeOptions validOptions = new DedupNodeOptions();
		ValidationResult validResult = validOptions.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}