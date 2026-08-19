package io.metaloom.loom.db.jooq.dao.failure;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.failure.FailureReport;

public class FailureReportImpl extends AbstractEditableElement<FailureReport> implements FailureReport {

	private String action;
	private String traceId;
	private String httpMethod;
	private String path;
	private Integer statusCode;
	private String errorMessage;
	private String route;
	private String userAgent;
	private String text;
	private String triageStatus;

	@Override
	public String getAction() {
		return action;
	}

	@Override
	public FailureReport setAction(String action) {
		this.action = action;
		return this;
	}

	@Override
	public String getTraceId() {
		return traceId;
	}

	@Override
	public FailureReport setTraceId(String traceId) {
		this.traceId = traceId;
		return this;
	}

	@Override
	public String getHttpMethod() {
		return httpMethod;
	}

	@Override
	public FailureReport setHttpMethod(String httpMethod) {
		this.httpMethod = httpMethod;
		return this;
	}

	@Override
	public String getPath() {
		return path;
	}

	@Override
	public FailureReport setPath(String path) {
		this.path = path;
		return this;
	}

	@Override
	public Integer getStatusCode() {
		return statusCode;
	}

	@Override
	public FailureReport setStatusCode(Integer statusCode) {
		this.statusCode = statusCode;
		return this;
	}

	@Override
	public String getErrorMessage() {
		return errorMessage;
	}

	@Override
	public FailureReport setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	@Override
	public String getRoute() {
		return route;
	}

	@Override
	public FailureReport setRoute(String route) {
		this.route = route;
		return this;
	}

	@Override
	public String getUserAgent() {
		return userAgent;
	}

	@Override
	public FailureReport setUserAgent(String userAgent) {
		this.userAgent = userAgent;
		return this;
	}

	@Override
	public String getText() {
		return text;
	}

	@Override
	public FailureReport setText(String text) {
		this.text = text;
		return this;
	}

	@Override
	public String getTriageStatus() {
		return triageStatus;
	}

	@Override
	public FailureReport setTriageStatus(String triageStatus) {
		this.triageStatus = triageStatus;
		return this;
	}
}
