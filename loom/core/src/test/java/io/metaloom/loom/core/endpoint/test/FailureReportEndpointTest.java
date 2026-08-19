package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.DELETE_FAILURE_REPORT;
import static io.metaloom.loom.db.model.perm.Permission.READ_FAILURE_REPORT;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_FAILURE_REPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.failure.FailureReportCreateRequest;
import io.metaloom.loom.rest.model.failure.FailureReportListResponse;
import io.metaloom.loom.rest.model.failure.FailureReportResponse;
import io.metaloom.loom.rest.model.failure.FailureReportUpdateRequest;

/**
 * Problem reports, at {@code /api/v1/failure-reports}.
 *
 * <p>
 * <b>Extends {@link AbstractEndpointTest} rather than {@code AbstractCRUDEndpointTest}</b> on purpose. That base class asserts that every verb -
 * create included - answers 403 for a permissionless caller, and here create must answer 201: the whole point of the route is that a user with no
 * grants at all can still report a failure. Inheriting the generic suite would have meant either weakening the feature or overriding its test to
 * assert the opposite of what it says, and both hide the decision. The other four verbs get their 403 case here explicitly instead.
 * </p>
 */
public class FailureReportEndpointTest extends AbstractEndpointTest {

	/** The smallest valid PNG: an 8-bit RGBA 1x1. Real enough to pass the magic-byte sniff. */
	private static final String PNG_1X1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

	@Test
	public void testCreateAndRead() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			FailureReportCreateRequest request = new FailureReportCreateRequest()
				.setAction("createPerson")
				.setTraceId("9f2c41ab7d0e4c6fa1b83e5d72c09148")
				.setHttpMethod("POST")
				.setPath("/api/v1/persons")
				.setStatusCode(500)
				.setErrorMessage("Internal Server Error")
				.setRoute("/detection")
				.setText("The dialog closed but the person is not in the list.");

			FailureReportResponse created = client.createFailureReport(request).sync().body();
			assertNotNull(created.getUuid());
			assertEquals("createPerson", created.getAction());
			assertEquals("9f2c41ab7d0e4c6fa1b83e5d72c09148", created.getTraceId());
			assertEquals(500, created.getStatusCode());
			assertEquals("NEW", created.getTriageStatus(), "A fresh report starts in triage state NEW");
			assertFalse(created.getHasScreenshot(), "No screenshot was sent");
			assertNull(created.getScreenshotUrl(), "A report without a screenshot must not advertise a URL for one");

			FailureReportResponse loaded = client.loadFailureReport(created.getUuid()).sync().body();
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("The dialog closed but the person is not in the list.", loaded.getText());
		}
	}

	/**
	 * The property the whole feature rests on: a user holding no permissions whatsoever can still tell somebody that
	 * something broke. If this ever starts failing, the product has lost its only response to a breakage for exactly
	 * the users most likely to hit one.
	 */
	@Test
	public void testCreateNeedsNoPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			FailureReportResponse created = client.createFailureReport(
				new FailureReportCreateRequest().setAction("loadLibraries").setText("The libraries screen is empty.")).sync().body();
			assertNotNull(created.getUuid());

			// ...but they still cannot read the inbox back.
			expect(403, "Forbidden", client.loadFailureReport(created.getUuid()));
			expect(403, "Forbidden", client.listFailureReports());
		}
	}

	@Test
	public void testCreateRequiresAnAction() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(400, "Bad Request", client.createFailureReport(new FailureReportCreateRequest().setText("something broke")));
			expect(400, "Bad Request", client.createFailureReport(new FailureReportCreateRequest().setAction("   ")));
		}
	}

	/**
	 * A report about a render throw carries no status code, no path and no trace id. It must still be accepted -
	 * those are the failures hardest to reproduce and most worth hearing about.
	 */
	@Test
	public void testCreateAcceptsAReportWithNoResponse() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			FailureReportResponse created = client.createFailureReport(
				new FailureReportCreateRequest().setAction("renderAssetGrid").setRoute("/assets").setText("The screen went blank.")).sync().body();

			assertNull(created.getStatusCode());
			assertNull(created.getTraceId());
			assertEquals("renderAssetGrid", created.getAction());
		}
	}

	/** A nonsensical status code is dropped, not rejected: a client bug must not swallow the user's report. */
	@Test
	public void testNonsenseStatusCodeIsDroppedRatherThanRejected() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			FailureReportResponse created = client.createFailureReport(
				new FailureReportCreateRequest().setAction("createTag").setStatusCode(99999)).sync().body();
			assertNull(created.getStatusCode(), "An out-of-range status code is dropped");
		}
	}

	@Test
	public void testUserAgentIsObservedNotDeclared() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			FailureReportResponse created = client.createFailureReport(
				new FailureReportCreateRequest().setAction("createTag")).sync().body();

			// The request model has no userAgent field at all, so whatever is here came from the request headers.
			FailureReportResponse loaded = client.loadFailureReport(created.getUuid()).sync().body();
			assertNotNull(loaded.getUserAgent(), "The server stamps the reporter's user agent from the request");
		}
	}

	@Test
	public void testScreenshotRoundTrip() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			FailureReportResponse created = client.createFailureReport(new FailureReportCreateRequest()
				.setAction("createPerson")
				// Sent as a data URL, which is what canvas.toDataURL() produces.
				.setScreenshot("data:image/png;base64," + PNG_1X1)
				.setScreenshotWidth(1)
				.setScreenshotHeight(1)).sync().body();

			assertTrue(created.getHasScreenshot(), "The screenshot was accepted");
			assertNotNull(created.getScreenshotUrl());
			assertTrue(created.getScreenshotUrl().endsWith("/failure-reports/" + created.getUuid() + "/screenshot"),
				"The screenshot URL points at the download route, got " + created.getScreenshotUrl());

			// The listing must report the attachment without inlining it.
			FailureReportListResponse list = client.listFailureReports().sync().body();
			FailureReportResponse listed = list.getData().stream()
				.filter(report -> report.getUuid().equals(created.getUuid()))
				.findFirst()
				.orElseThrow();
			assertTrue(listed.getHasScreenshot());
		}
	}

	/** The declared mime type is ignored; the bytes decide. A JPEG announced as a PNG comes back as a JPEG. */
	@Test
	public void testScreenshotTypeComesFromTheBytes() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// A minimal JPEG header, announced in the data URL as a PNG.
			byte[] jpeg = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F' };
			String encoded = Base64.getEncoder().encodeToString(jpeg);

			FailureReportResponse created = client.createFailureReport(new FailureReportCreateRequest()
				.setAction("createPerson")
				.setScreenshot("data:image/png;base64," + encoded)).sync().body();

			assertTrue(created.getHasScreenshot());
			assertEquals("image/jpeg", loom.internal().daos().failureReportDao().loadScreenshot(created.getUuid()).getMimeType(),
				"The stored type is sniffed from the bytes, not taken from the data URL");
		}
	}

	@Test
	public void testScreenshotMustBeAnImage() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			String notAnImage = Base64.getEncoder().encodeToString("<html>hello</html>".getBytes());
			expect(400, "Bad Request", client.createFailureReport(
				new FailureReportCreateRequest().setAction("createPerson").setScreenshot(notAnImage)));
		}
	}

	@Test
	public void testOversizedScreenshotIsRejected() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// 8 MB of base64 is comfortably over the 5 MB decoded cap and is rejected on length alone.
			String oversized = "A".repeat(8 * 1024 * 1024);
			expect(413, "Request Entity Too Large", client.createFailureReport(
				new FailureReportCreateRequest().setAction("createPerson").setScreenshot(oversized)));
		}
	}

	@Test
	public void testTriage() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			FailureReportResponse created = client.createFailureReport(
				new FailureReportCreateRequest().setAction("createTag")).sync().body();

			FailureReportResponse updated = client
				.updateFailureReport(created.getUuid(), new FailureReportUpdateRequest().setTriageStatus("ACKNOWLEDGED"))
				.sync().body();
			assertEquals("ACKNOWLEDGED", updated.getTriageStatus());

			expect(400, "Bad Request",
				client.updateFailureReport(created.getUuid(), new FailureReportUpdateRequest().setTriageStatus("MAYBE_LATER")));
		}
	}

	@Test
	public void testDeleteTakesTheScreenshotWithIt() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			FailureReportResponse created = client.createFailureReport(new FailureReportCreateRequest()
				.setAction("createPerson")
				.setScreenshot("data:image/png;base64," + PNG_1X1)).sync().body();
			UUID uuid = created.getUuid();
			assertNotNull(loom.internal().daos().failureReportDao().loadScreenshot(uuid));

			client.deleteFailureReport(uuid).sync().body();

			expect(404, "Not Found", client.loadFailureReport(uuid));
			assertNull(loom.internal().daos().failureReportDao().loadScreenshot(uuid),
				"failure_report_screenshot is ON DELETE CASCADE, so the image goes with the report");
		}
	}

	@Test
	public void testLoadUnknownReport() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.loadFailureReport(UUID.randomUUID()));
		}
	}

	// --- RBAC ---

	@Test
	public void testReadRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.loadFailureReport(UUID.randomUUID()));
		}
	}

	@Test
	public void testListRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.listFailureReports());
		}
	}

	@Test
	public void testUpdateRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden",
				client.updateFailureReport(UUID.randomUUID(), new FailureReportUpdateRequest().setTriageStatus("RESOLVED")));
		}
	}

	@Test
	public void testDeleteRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.deleteFailureReport(UUID.randomUUID()));
		}
	}

	/**
	 * Reading and triaging are separate grants: a reader may not change the state of what they read.
	 */
	@Test
	public void testReaderCannotTriage() throws Exception {
		UUID uuid;
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			uuid = client.createFailureReport(new FailureReportCreateRequest().setAction("createTag")).sync().body().getUuid();
		}
		try (LoomHttpClient client = loginClientWith("report-reader", READ_FAILURE_REPORT)) {
			assertEquals(uuid, client.loadFailureReport(uuid).sync().body().getUuid());
			expect(403, "Forbidden", client.updateFailureReport(uuid, new FailureReportUpdateRequest().setTriageStatus("RESOLVED")));
			expect(403, "Forbidden", client.deleteFailureReport(uuid));
		}
		try (LoomHttpClient client = loginClientWith("report-triager", READ_FAILURE_REPORT, UPDATE_FAILURE_REPORT)) {
			assertEquals("RESOLVED",
				client.updateFailureReport(uuid, new FailureReportUpdateRequest().setTriageStatus("RESOLVED")).sync().body().getTriageStatus());
		}
		try (LoomHttpClient client = loginClientWith("report-deleter", DELETE_FAILURE_REPORT)) {
			client.deleteFailureReport(uuid).sync().body();
		}
	}
}
