package io.metaloom.cortex.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class CloudFileRefTest {

	private static CloudFileRef file(String name, String parentId, String token, long size) {
		return new CloudFileRef(CloudProviderId.GDRIVE, "d", "f1", name, parentId, "video/mp4",
			token, size, 0, false, false, null, true);
	}

	@Test
	public void testDiffersOnChangeToken() {
		assertThat(file("a.mp4", "p", "md5:aaa", 10).differsFrom("md5:bbb", 10)).isTrue();
		assertThat(file("a.mp4", "p", "md5:aaa", 10).differsFrom("md5:aaa", 10)).isFalse();
	}

	@Test
	public void testDiffersOnSize() {
		assertThat(file("a.mp4", "p", "md5:aaa", 20).differsFrom("md5:aaa", 10)).isTrue();
	}

	@Test
	public void testMissingTokenDegradesToSizeOnly() {
		// A provider that withheld a token must not make every file look modified on every run.
		assertThat(file("a.mp4", "p", null, 10).differsFrom("md5:aaa", 10)).isFalse();
		assertThat(file("a.mp4", "p", "md5:aaa", 10).differsFrom(null, 10)).isFalse();
	}

	@Test
	public void testMovedIsDetectedOnAParentChange() {
		assertThat(file("a.mp4", "new-parent", "t", 1).movedFrom("old-parent", "a.mp4")).isTrue();
	}

	@Test
	public void testMovedIsDetectedOnARename() {
		assertThat(file("b.mp4", "p", "t", 1).movedFrom("p", "a.mp4")).isTrue();
	}

	@Test
	public void testUnchangedIsNeitherModifiedNorMoved() {
		CloudFileRef ref = file("a.mp4", "p", "t", 1);
		assertThat(ref.differsFrom("t", 1)).isFalse();
		assertThat(ref.movedFrom("p", "a.mp4")).isFalse();
	}

	@Test
	public void testReferenceCarriesTheNameAsItsLastSegment() {
		assertThat(file("a.mp4", "p", "t", 1).reference()).isEqualTo("gdrive://d/f1/a.mp4");
	}

	@Test
	public void testAnExportedDocumentGetsTheExportExtension() {
		CloudFileRef doc = new CloudFileRef(CloudProviderId.GDRIVE, "d", "f2", "Q3 Report", "p",
			"application/vnd.google-apps.document", "v:3", -1, 0, false, false, "application/pdf", true);
		assertThat(doc.requiresExport()).isTrue();
		assertThat(doc.effectiveName()).isEqualTo("Q3 Report.pdf");
		assertThat(doc.reference()).endsWith("/Q3 Report.pdf");
	}

	@Test
	public void testAnAbsentRefIsNotPresentAndHasNoSize() {
		CloudFileRef absent = CloudFileRef.absent(CloudUri.parse("gdrive://d/gone/x.mp4"));
		assertThat(absent.present()).isFalse();
		assertThat(absent.size()).isEqualTo(-1);
	}

	@Test
	public void testABlankNameFallsBackToTheFileId() {
		CloudFileRef ref = new CloudFileRef(CloudProviderId.ONEDRIVE, "d", "f9", null, null, null,
			null, 1, 0, false, false, null, true);
		assertThat(ref.name()).isEqualTo("f9");
	}
}
