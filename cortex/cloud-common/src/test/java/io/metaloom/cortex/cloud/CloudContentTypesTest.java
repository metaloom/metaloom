package io.metaloom.cortex.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class CloudContentTypesTest {

	@Test
	public void testExtensionForTheGoogleExportTargets() {
		assertThat(CloudContentTypes.extensionFor("application/pdf")).isEqualTo(".pdf");
		assertThat(CloudContentTypes.extensionFor("text/csv")).isEqualTo(".csv");
		assertThat(CloudContentTypes.extensionFor("image/png")).isEqualTo(".png");
	}

	@Test
	public void testCasingAndParametersAreIgnored() {
		assertThat(CloudContentTypes.extensionFor("Application/PDF")).isEqualTo(".pdf");
		assertThat(CloudContentTypes.extensionFor("text/csv; charset=utf-8")).isEqualTo(".csv");
	}

	@Test
	public void testUnknownAndMissingTypesFallBack() {
		assertThat(CloudContentTypes.extensionFor("application/x-made-up")).isEqualTo(".bin");
		assertThat(CloudContentTypes.extensionFor(null)).isEqualTo(".bin");
		assertThat(CloudContentTypes.extensionFor("  ")).isEqualTo(".bin");
	}
}
