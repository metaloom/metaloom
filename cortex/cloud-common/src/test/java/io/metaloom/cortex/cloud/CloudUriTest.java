package io.metaloom.cortex.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

public class CloudUriTest {

	@Test
	public void testGdriveRoundTrip() {
		CloudUri uri = CloudUri.parse("gdrive://drive-1/file-9/clip.mp4");
		assertThat(uri.provider()).isEqualTo(CloudProviderId.GDRIVE);
		assertThat(uri.driveId()).isEqualTo("drive-1");
		assertThat(uri.fileId()).isEqualTo("file-9");
		assertThat(uri.fileName()).isEqualTo("clip.mp4");
		assertThat(uri.toReference()).isEqualTo("gdrive://drive-1/file-9/clip.mp4");
	}

	@Test
	public void testOneDriveRoundTrip() {
		CloudUri uri = CloudUri.parse("onedrive://b!abc/01ABC/report.pdf");
		assertThat(uri.provider()).isEqualTo(CloudProviderId.ONEDRIVE);
		assertThat(uri.driveId()).isEqualTo("b!abc");
		assertThat(uri.toReference()).isEqualTo("onedrive://b!abc/01ABC/report.pdf");
	}

	@Test
	public void testMyDrivePlaceholderKeepsThreeSegments() {
		CloudUri uri = new CloudUri(CloudProviderId.GDRIVE, CloudUri.MY_DRIVE, "f1", "a.jpg");
		assertThat(uri.isMyDrive()).isTrue();
		assertThat(CloudUri.parse(uri.toReference())).isEqualTo(uri);
	}

	@Test
	public void testIsCloudRecognisesBothSchemesAndNothingElse() {
		assertThat(CloudUri.isCloud("gdrive://d/f/n.mp4")).isTrue();
		assertThat(CloudUri.isCloud("onedrive://d/f/n.mp4")).isTrue();
		assertThat(CloudUri.isCloud("s3://bucket/key")).isFalse();
		assertThat(CloudUri.isCloud("/media/clip.mp4")).isFalse();
		assertThat(CloudUri.isCloud(null)).isFalse();
	}

	@Test
	public void testExtensionIsPreservedForMediaTypeDetection() {
		assertThat(CloudUri.parse("gdrive://d/f/clip.MP4").extension()).isEqualTo(".MP4");
		assertThat(CloudUri.parse("gdrive://d/f/no-extension").extension()).isEmpty();
	}

	@Test
	public void testExtensionGuardRejectsALongOrOddTrailingSegment() {
		// A dot inside a name is not an extension.
		assertThat(CloudUri.parse("gdrive://d/f/report.2026-final-version").extension()).isEmpty();
		assertThat(CloudUri.parse("gdrive://d/f/a.b c").extension()).isEmpty();
	}

	@Test
	public void testSlashInNameIsSanitized() {
		// Drive allows a slash in a file name; the reference's last segment must stay one segment,
		// because Loom derives the asset filename from it with Paths.getFileName().
		CloudUri uri = new CloudUri(CloudProviderId.GDRIVE, "d", "f", "2026/07 recap.mp4");
		assertThat(uri.fileName()).isEqualTo("2026_07 recap.mp4");
		assertThat(CloudUri.parse(uri.toReference()).fileId()).isEqualTo("f");
	}

	@Test
	public void testBlankNameFallsBackRatherThanProducingAnEmptySegment() {
		assertThat(new CloudUri(CloudProviderId.GDRIVE, "d", "f", null).fileName()).isEqualTo("file");
		assertThat(new CloudUri(CloudProviderId.GDRIVE, "d", "f", "   ").fileName()).isEqualTo("file");
	}

	@Test
	public void testTheLastSegmentSurvivesTheFileNameApi() {
		// This is the property Loom's asset sink depends on: Paths.get collapses the double slash,
		// but the file name - and therefore the extension and MIME type - still come out right.
		String reference = "gdrive://d/f/clip.mp4";
		assertThat(Paths.get(reference).getFileName().toString()).isEqualTo("clip.mp4");
	}

	@Test
	public void testRejectsAForeignSchemeAndAMissingFileId() {
		assertThatThrownBy(() -> CloudUri.parse("s3://bucket/key"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Not a cloud media reference");
		assertThatThrownBy(() -> CloudUri.parse("gdrive://only-a-drive"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("missing a file id");
	}

	@Test
	public void testANameLessReferenceIsStillAddressable() {
		CloudUri uri = CloudUri.parse("gdrive://d/f");
		assertThat(uri.fileId()).isEqualTo("f");
		assertThat(uri.extension()).isEmpty();
	}
}
