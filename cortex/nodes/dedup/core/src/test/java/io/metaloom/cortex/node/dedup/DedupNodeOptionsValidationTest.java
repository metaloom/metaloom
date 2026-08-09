package io.metaloom.cortex.node.dedup;

import static io.metaloom.cortex.node.dedup.assertj.DedupNodeAssertions.assertThat;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class DedupNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		DedupNodeOptions options = new DedupNodeOptions();
		assertThat(options).isValid();
	}

	/**
	 * No keeper-exclude folder is the default, and it means the check is simply off.
	 *
	 * <p>
	 * This replaces the old {@code dupFolder} assertions. That option had a default of {@code duplicates} and could not be null, because the node
	 * moved files into it; the relocation now happens on a downstream {@code move} node, so the only folder these options still care about is the one
	 * a keeper must <b>not</b> be in - and having none of those is perfectly normal.
	 * </p>
	 */
	@Test
	public void testNoKeeperExcludeFolderByDefault() {
		assertThat(new DedupNodeOptions()).isValid().hasNoKeepExcludeFolder();
	}

	@Test
	public void testACustomKeeperExcludeFolderIsValid() {
		DedupNodeOptions options = new DedupNodeOptions();
		options.setKeepExcludeFolder(Paths.get("/data/trash"));
		assertThat(options).isValid().hasKeepExcludeFolder(Paths.get("/data/trash"));
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
		options.setTimeoutMs(-1);

		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("timeoutMs must be non-negative");

		DedupNodeOptions validOptions = new DedupNodeOptions();
		ValidationResult validResult = validOptions.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}
