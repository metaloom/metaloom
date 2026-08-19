package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.DELETE_FAILURE_REPORT;
import static io.metaloom.loom.db.model.perm.Permission.READ_FAILURE_REPORT;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_FAILURE_REPORT;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.failure.FailureReport;
import io.metaloom.loom.db.model.failure.FailureReportDao;
import io.metaloom.loom.db.model.failure.FailureReportScreenshot;
import io.metaloom.loom.db.model.failure.FailureReportTriageStatus;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.failure.FailureReportCreateRequest;
import io.metaloom.loom.rest.model.failure.FailureReportListResponse;
import io.metaloom.loom.rest.model.failure.FailureReportResponse;
import io.metaloom.loom.rest.model.failure.FailureReportUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * Problem reports submitted from the UI.
 *
 * <p>
 * <b>Creating one requires authentication and no permission at all.</b> That is the one design decision in this class worth arguing about, so: a
 * permission to report a failure would, on any upgraded installation where nobody thought to grant it, turn the product's single response to a
 * breakage into a 403 - and the people most likely to hit that are exactly the ones with the fewest grants. The row is created against the caller's
 * own uuid and describes their own session, so there is nothing here to escalate into. Reading the inbox is a different matter and does take a
 * permission, because a report may carry a screenshot of assets its reader is not otherwise cleared to see. See V2.106.
 * </p>
 */
@Singleton
public class FailureReportEndpointService extends AbstractCRUDEndpointService<FailureReportDao, FailureReport> {

	/**
	 * Largest screenshot accepted, in decoded bytes.
	 *
	 * <p>
	 * A full-resolution PNG of a 4K screen is around 3 MB, and the UI downscales before it encodes. Five leaves room without letting a single report
	 * put an arbitrary blob in the database.
	 * </p>
	 */
	static final int MAX_SCREENSHOT_BYTES = 5 * 1024 * 1024;

	/** Longest accepted value for any of the short descriptive fields. Prose goes in {@code text}, which is not capped here. */
	static final int MAX_FIELD_LENGTH = 1024;

	/** Longest accepted report text. Long enough for a considered description, short enough not to be a file upload. */
	static final int MAX_TEXT_LENGTH = 16 * 1024;

	@Inject
	public FailureReportEndpointService(FailureReportDao failureReportDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(failureReportDao, daos, modelBuilder, validator);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		FailureReportCreateRequest request = lrc.requestBody(FailureReportCreateRequest.class);
		validator.validate(request);

		String action = trimToNull(request.getAction());
		if (action == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "A failure report must name the action it is about.");
		}

		// Decoded and checked BEFORE the row is stored. A screenshot that is too large or is not an image should
		// answer 4xx and leave nothing behind, rather than leaving a report in the inbox whose attachment silently
		// vanished.
		byte[] screenshot = decodeScreenshot(request.getScreenshot());

		FailureReport report = dao().createFailureReport(lrc.userUuid(), truncate(action));
		report.setTraceId(truncate(trimToNull(request.getTraceId())));
		report.setHttpMethod(truncate(trimToNull(request.getHttpMethod())));
		report.setPath(truncate(trimToNull(request.getPath())));
		report.setStatusCode(validStatusCode(request.getStatusCode()));
		report.setErrorMessage(truncate(trimToNull(request.getErrorMessage())));
		report.setRoute(truncate(trimToNull(request.getRoute())));
		report.setText(truncate(trimToNull(request.getText()), MAX_TEXT_LENGTH));
		// Observed, not declared - see FailureReport#getUserAgent.
		report.setUserAgent(truncate(userAgentOf(lrc)));

		dao().store(report);

		if (screenshot != null) {
			dao().storeScreenshot(report.getUuid(), sniffImageType(screenshot), request.getScreenshotWidth(), request.getScreenshotHeight(),
				screenshot);
		}

		lrc.send(toAbsolute(lrc, modelBuilder.toResponse(report, screenshot != null)), 201);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_FAILURE_REPORT, () -> {
			FailureReportUpdateRequest request = lrc.requestBody(FailureReportUpdateRequest.class);
			validator.validate(request);

			FailureReport report = loadReport(uuid);

			// The triage state is the only editable field, so an update that names none is a no-op the caller almost
			// certainly did not mean. Rejecting it is more useful than silently touching `edited`.
			FailureReportTriageStatus status = FailureReportTriageStatus.parse(request.getTriageStatus());
			if (status == null) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
					"triageStatus must be one of NEW, ACKNOWLEDGED, RESOLVED.");
			}
			report.setTriageStatus(status);
			report.setEditorUuid(lrc.userUuid());
			report.setEdited(java.time.Instant.now());
			return report;
		}, report -> toAbsolute(lrc, modelBuilder.toResponse(report, dao().hasScreenshot(report.getUuid()))));
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_FAILURE_REPORT, () -> dao().load(uuid),
			report -> toAbsolute(lrc, modelBuilder.toResponse(report, dao().hasScreenshot(report.getUuid()))));
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_FAILURE_REPORT, page -> absolutise(lrc, buildList(page)));
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		// The screenshot goes with it: failure_report_screenshot is ON DELETE CASCADE.
		delete(lrc, DELETE_FAILURE_REPORT, uuid);
	}

	/**
	 * Stream a report's screenshot.
	 *
	 * <p>
	 * A route of its own rather than a field on the response, so that listing the inbox never drags images behind it. The stored mime type is echoed
	 * back and was derived from the bytes at write time, not from anything the uploader said.
	 * </p>
	 */
	public void loadScreenshot(LoomRoutingContext lrc, UUID uuid) {
		checkPerm(lrc, READ_FAILURE_REPORT, () -> {
			FailureReportScreenshot screenshot = dao().loadScreenshot(uuid);
			if (screenshot == null) {
				// 404 whether the report is absent or merely has no screenshot. Distinguishing the two would leak the
				// existence of reports to a caller who may read them anyway, which buys nothing.
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No screenshot for failure report " + uuid);
			}
			HttpServerResponse response = lrc.routingContext().response();
			response.putHeader("Content-Type", screenshot.getMimeType());
			// Not inline: a screenshot may show anything that was on a user's screen, and letting the browser render
			// it in a top-level navigation on the API origin is a needless way to host attacker-influenced content.
			response.putHeader("Content-Disposition", "attachment; filename=\"failure-report-" + uuid + imageExtension(screenshot.getMimeType()) + "\"");
			response.putHeader("X-Content-Type-Options", "nosniff");
			response.end(Buffer.buffer(screenshot.getData()));
		});
	}

	private FailureReportListResponse buildList(Page<FailureReport> page) {
		// Page is Iterable, not a List. One pass to collect the keys, one query to answer the whole page.
		List<UUID> uuids = new ArrayList<>();
		page.forEach(report -> uuids.add(report.getUuid()));
		Set<UUID> withScreenshot = dao().screenshotUuids(uuids);
		return modelBuilder.toFailureReportList(page, withScreenshot);
	}

	private FailureReport loadReport(UUID uuid) {
		FailureReport report = dao().load(uuid);
		if (report == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Failure report not found " + uuid);
		}
		return report;
	}

	/**
	 * Decode the submitted screenshot, or return null when none was sent.
	 *
	 * @throws LoomRestException
	 *             413 when it exceeds {@link #MAX_SCREENSHOT_BYTES}, 400 when it is not decodable base64 or not a supported image
	 */
	private byte[] decodeScreenshot(String submitted) {
		String encoded = trimToNull(submitted);
		if (encoded == null) {
			return null;
		}
		// A data URL is what canvas.toDataURL() produces, so accepting one saves every caller the same string surgery.
		int comma = encoded.startsWith("data:") ? encoded.indexOf(',') : -1;
		if (comma >= 0) {
			encoded = encoded.substring(comma + 1);
		}
		// Checked before decoding: base64 expands to about 3/4 its length, so this rejects an oversized payload
		// without materialising it.
		if ((long) encoded.length() / 4 * 3 > MAX_SCREENSHOT_BYTES) {
			throw new LoomRestException(413, LoomRestErrorCode.BAD_REQUEST,
				"The screenshot exceeds the " + (MAX_SCREENSHOT_BYTES / (1024 * 1024)) + " MB limit.");
		}
		byte[] data;
		try {
			data = Base64.getDecoder().decode(encoded);
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The screenshot is not valid base64.");
		}
		if (data.length > MAX_SCREENSHOT_BYTES) {
			throw new LoomRestException(413, LoomRestErrorCode.BAD_REQUEST,
				"The screenshot exceeds the " + (MAX_SCREENSHOT_BYTES / (1024 * 1024)) + " MB limit.");
		}
		if (data.length == 0) {
			return null;
		}
		// Throws when the bytes are not an image this endpoint serves back.
		sniffImageType(data);
		return data;
	}

	/**
	 * Determine the image type from the bytes themselves.
	 *
	 * <p>
	 * Deliberately not taken from a client-supplied mime type or from the data URL's own prefix. This value is echoed back as the
	 * {@code Content-Type} of {@link #loadScreenshot}, so letting the uploader choose it would let them have arbitrary bytes served under a type of
	 * their choosing from the API origin.
	 * </p>
	 */
	static String sniffImageType(byte[] data) {
		if (startsWith(data, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
			return "image/png";
		}
		if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
			return "image/jpeg";
		}
		if (startsWith(data, 'R', 'I', 'F', 'F') && data.length > 11 && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
			return "image/webp";
		}
		throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The screenshot must be a PNG, JPEG or WebP image.");
	}

	private static boolean startsWith(byte[] data, int... magic) {
		if (data.length < magic.length) {
			return false;
		}
		for (int i = 0; i < magic.length; i++) {
			if ((data[i] & 0xFF) != (magic[i] & 0xFF)) {
				return false;
			}
		}
		return true;
	}

	private static String imageExtension(String mimeType) {
		if ("image/jpeg".equals(mimeType)) {
			return ".jpg";
		}
		if ("image/webp".equals(mimeType)) {
			return ".webp";
		}
		return ".png";
	}

	/**
	 * Reject a status code that is not one. Out-of-range values are dropped rather than refused: a client bug in the reporting path must not stop
	 * somebody reporting the bug they actually cared about.
	 */
	private static Integer validStatusCode(Integer code) {
		if (code == null || code < 100 || code > 599) {
			return null;
		}
		return code;
	}

	private static String userAgentOf(LoomRoutingContext lrc) {
		HttpServerRequest request = lrc.routingContext().request();
		return request == null ? null : trimToNull(request.getHeader("User-Agent"));
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static String truncate(String value) {
		return truncate(value, MAX_FIELD_LENGTH);
	}

	/**
	 * Cut an over-long field rather than rejecting the report.
	 *
	 * <p>
	 * These columns are unbounded {@code varchar}, so this is not a schema requirement - it is a refusal to let one report carry a megabyte of error
	 * string. Truncating beats rejecting here for the same reason as {@link #validStatusCode}: the submission is the user's only channel, and losing
	 * the tail of a stack trace is a smaller harm than losing the report.
	 * </p>
	 */
	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max);
	}

	/**
	 * Rewrite the relative screenshot URL against the host the caller actually reached, mirroring {@code ShareLinkEndpointService}.
	 */
	private FailureReportResponse toAbsolute(LoomRoutingContext lrc, FailureReportResponse response) {
		String base = externalBaseUrl(lrc);
		if (base != null && response.getScreenshotUrl() != null && response.getScreenshotUrl().startsWith("/")) {
			response.setScreenshotUrl(base + response.getScreenshotUrl());
		}
		return response;
	}

	private FailureReportListResponse absolutise(LoomRoutingContext lrc, FailureReportListResponse list) {
		list.getData().forEach(response -> toAbsolute(lrc, response));
		return list;
	}

	private String externalBaseUrl(LoomRoutingContext lrc) {
		HttpServerRequest request = lrc.routingContext().request();
		if (request == null) {
			return null;
		}
		String proto = request.getHeader("X-Forwarded-Proto");
		String host = request.getHeader("X-Forwarded-Host");
		if (host == null) {
			host = request.getHeader("Host");
		}
		if (host == null) {
			return null;
		}
		if (proto == null) {
			proto = request.isSSL() ? "https" : "http";
		}
		// A forwarded header may carry a list; the first hop is the one the client saw.
		int comma = proto.indexOf(',');
		if (comma >= 0) {
			proto = proto.substring(0, comma).trim();
		}
		comma = host.indexOf(',');
		if (comma >= 0) {
			host = host.substring(0, comma).trim();
		}
		return proto + "://" + host;
	}
}
