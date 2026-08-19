package io.metaloom.loom.rest.model.failure;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

/**
 * One problem report, as the inbox sees it.
 *
 * <p>
 * The screenshot is <b>never</b> inlined here. It is fetched from {@link #getScreenshotUrl()}, so that listing a page of reports stays a listing rather
 * than a multi-megabyte download of images nobody has opened yet.
 * </p>
 */
public class FailureReportResponse extends AbstractCreatorEditorRestResponse<FailureReportResponse> {

	@JsonPropertyDescription("What the user was doing, in the client's own vocabulary.")
	private String action;

	@JsonPropertyDescription("The X-Trace-Id of the failing response. Quote this to find the matching server log entry.")
	private String traceId;

	@JsonPropertyDescription("HTTP method of the failing request.")
	private String httpMethod;

	@JsonPropertyDescription("Path of the failing request.")
	private String path;

	@JsonPropertyDescription("HTTP status of the failing response, or null when the failure produced none.")
	private Integer statusCode;

	@JsonPropertyDescription("The error message the client showed the user.")
	private String errorMessage;

	@JsonPropertyDescription("The client-side route the user was on.")
	private String route;

	@JsonPropertyDescription("The reporter's user agent, as observed by the server.")
	private String userAgent;

	@JsonPropertyDescription("What the user typed.")
	private String text;

	@JsonPropertyDescription("Triage state: NEW, ACKNOWLEDGED or RESOLVED. Not called `status` because that property already carries the creator/editor audit block.")
	private String triageStatus;

	@JsonPropertyDescription("Whether a screenshot is attached. Answered without reading the image.")
	private Boolean hasScreenshot;

	@JsonPropertyDescription("Where to fetch the screenshot, or null when there is none. Never inlined into this response.")
	private String screenshotUrl;

	public String getAction() {
		return action;
	}

	public FailureReportResponse setAction(String action) {
		this.action = action;
		return this;
	}

	public String getTraceId() {
		return traceId;
	}

	public FailureReportResponse setTraceId(String traceId) {
		this.traceId = traceId;
		return this;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public FailureReportResponse setHttpMethod(String httpMethod) {
		this.httpMethod = httpMethod;
		return this;
	}

	public String getPath() {
		return path;
	}

	public FailureReportResponse setPath(String path) {
		this.path = path;
		return this;
	}

	public Integer getStatusCode() {
		return statusCode;
	}

	public FailureReportResponse setStatusCode(Integer statusCode) {
		this.statusCode = statusCode;
		return this;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public FailureReportResponse setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	public String getRoute() {
		return route;
	}

	public FailureReportResponse setRoute(String route) {
		this.route = route;
		return this;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public FailureReportResponse setUserAgent(String userAgent) {
		this.userAgent = userAgent;
		return this;
	}

	public String getText() {
		return text;
	}

	public FailureReportResponse setText(String text) {
		this.text = text;
		return this;
	}

	public String getTriageStatus() {
		return triageStatus;
	}

	public FailureReportResponse setTriageStatus(String triageStatus) {
		this.triageStatus = triageStatus;
		return this;
	}

	public Boolean getHasScreenshot() {
		return hasScreenshot;
	}

	public FailureReportResponse setHasScreenshot(Boolean hasScreenshot) {
		this.hasScreenshot = hasScreenshot;
		return this;
	}

	public String getScreenshotUrl() {
		return screenshotUrl;
	}

	public FailureReportResponse setScreenshotUrl(String screenshotUrl) {
		this.screenshotUrl = screenshotUrl;
		return this;
	}

	@Override
	public FailureReportResponse self() {
		return this;
	}
}
