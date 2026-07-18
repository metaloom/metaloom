package io.metaloom.cortex.node.thumbnail;

import static io.metaloom.cortex.node.thumbnail.assertj.ThumbnailNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class ThumbnailNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testCustomTileSizeValid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setTileSize(512);
		assertThat(options)
			.isValid().hasTileSize(512);
	}

	@Test
	public void testZeroTileSizeInvalid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setTileSize(0);
		assertThat(options).isInvalid().hasError("tileSize must be positive");
	}

	@Test
	public void testNegativeTileSizeInvalid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setTileSize(-1);
		assertThat(options).isInvalid().hasError("tileSize must be positive");
	}

	@Test
	public void testCustomColsValid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setCols(8);
		assertThat(options)
			.isValid().hasCols(8);
	}

	@Test
	public void testZeroColsInvalid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setCols(0);
		assertThat(options).isInvalid().hasError("cols must be positive");
	}

	@Test
	public void testNegativeColsInvalid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setCols(-1);
		assertThat(options).isInvalid().hasError("cols must be positive");
	}

	@Test
	public void testCustomRowsValid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setRows(2);
		assertThat(options)
			.isValid().hasRows(2);
	}

	@Test
	public void testZeroRowsInvalid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setRows(0);
		assertThat(options).isInvalid().hasError("rows must be positive");
	}

	@Test
	public void testNegativeRowsInvalid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setRows(-1);
		assertThat(options).isInvalid().hasError("rows must be positive");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		options.setTileSize(0);
		
		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("tileSize must be positive");
		
		ThumbnailNodeOptions validOptions = new ThumbnailNodeOptions();
		ValidationResult validResult = validOptions.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}