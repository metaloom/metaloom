package io.metaloom.loom.rest.model.failure;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * A user reporting that something went wrong.
 *
 * <p>
 * <b>Only {@code action} is required.</b> Everything describing the failing request is optional, because the failures worth reporting include ones
 * that produced no response at all - a render throw, a socket that closed, a screen that simply stayed empty. A form that insisted on a status code
 * would refuse exactly the reports that are hardest to reproduce.
 * </p>
 *
 * <p>
 * <b>There is no {@code userAgent} field</b> and there deliberately never will be: the server stamps it from the request headers. A report whose
 * provenance is self-declared is worth less than one whose provenance is observed. The request fields below are the opposite case - they describe an
 * <i>earlier</i> request, which the server cannot observe at all, so the client is the only possible source.
 * </p>
 */
public class FailureReportCreateRequest implements RestRequestModel {

	@JsonPropertyDescription("What the user was doing, in the client's own vocabulary - \"createPerson\", \"deleteTag\". Required. Answers \"which button\", where path answers \"which route\".")
	private String action;

	@JsonPropertyDescription("The X-Trace-Id of the failing response. The one value that lets an operator find the matching stack trace - send it whenever there was a response.")
	private String traceId;

	@JsonPropertyDescription("HTTP method of the failing request.")
	private String httpMethod;

	@JsonPropertyDescription("Path of the failing request.")
	private String path;

	@JsonPropertyDescription("HTTP status of the failing response, or null when the failure produced none.")
	private Integer statusCode;

	@JsonPropertyDescription("The error message the client showed the user, verbatim.")
	private String errorMessage;

	@JsonPropertyDescription("The client-side route the user was on, e.g. \"/detection\".")
	private String route;

	@JsonPropertyDescription("What the user typed about what they expected and what happened instead.")
	private String text;

	@JsonPropertyDescription("An optional screenshot, base64 encoded. A data URL (\"data:image/png;base64,...\") is accepted and its prefix stripped. PNG, JPEG and WebP only.")
	private String screenshot;

	@JsonPropertyDescription("Pixel width of the screenshot, used only to lay out its preview.")
	private Integer screenshotWidth;

	@JsonPropertyDescription("Pixel height of the screenshot.")
	private Integer screenshotHeight;

	public String getAction() {
		return action;
	}

	public FailureReportCreateRequest setAction(String action) {
		this.action = action;
		return this;
	}

	public String getTraceId() {
		return traceId;
	}

	public FailureReportCreateRequest setTraceId(String traceId) {
		this.traceId = traceId;
		return this;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public FailureReportCreateRequest setHttpMethod(String httpMethod) {
		this.httpMethod = httpMethod;
		return this;
	}

	public String getPath() {
		return path;
	}

	public FailureReportCreateRequest setPath(String path) {
		this.path = path;
		return this;
	}

	public Integer getStatusCode() {
		return statusCode;
	}

	public FailureReportCreateRequest setStatusCode(Integer statusCode) {
		this.statusCode = statusCode;
		return this;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public FailureReportCreateRequest setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	public String getRoute() {
		return route;
	}

	public FailureReportCreateRequest setRoute(String route) {
		this.route = route;
		return this;
	}

	public String getText() {
		return text;
	}

	public FailureReportCreateRequest setText(String text) {
		this.text = text;
		return this;
	}

	public String getScreenshot() {
		return screenshot;
	}

	public FailureReportCreateRequest setScreenshot(String screenshot) {
		this.screenshot = screenshot;
		return this;
	}

	public Integer getScreenshotWidth() {
		return screenshotWidth;
	}

	public FailureReportCreateRequest setScreenshotWidth(Integer screenshotWidth) {
		this.screenshotWidth = screenshotWidth;
		return this;
	}

	public Integer getScreenshotHeight() {
		return screenshotHeight;
	}

	public FailureReportCreateRequest setScreenshotHeight(Integer screenshotHeight) {
		this.screenshotHeight = screenshotHeight;
		return this;
	}
}
