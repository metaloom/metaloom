package io.metaloom.loom.db.model.failure;

import io.metaloom.loom.db.CUDElement;

/**
 * A problem report a user submitted from the UI.
 *
 * <p>
 * <b>What this carries that the server log cannot.</b> A failure is logged with a path, a status and a stack trace; none of that says what the user was
 * trying to do or what they expected instead. This row holds that half. {@link #getTraceId()} is what joins the two - it is the {@code X-Trace-Id} the
 * server stamped on the failing response, so an operator holding a report can find the exact stack trace behind it, and an operator holding a log line
 * can find out who it hurt.
 * </p>
 *
 * <p>
 * <b>Every field describing the request is nullable</b>, because the failures most worth reporting include the ones that produced no response at all -
 * a render throw, a socket that closed, a screen that simply stayed empty. A form that insisted on a status code would refuse exactly the reports that
 * are hardest to reproduce.
 * </p>
 *
 * <p>
 * {@link #getCreatorUuid()} is nullable, unlike most {@link CUDElement}s: the FK is {@code ON DELETE SET NULL}, because deleting the person who
 * reported a bug must not delete the bug.
 * </p>
 *
 * @see FailureReportDao
 */
public interface FailureReport extends CUDElement<FailureReport> {

	/**
	 * What the user was doing, in the client's own vocabulary - {@code "createPerson"}, {@code "deleteTag"}, {@code "loadLibraries"}.
	 *
	 * <p>
	 * Stamped by the caller at the call site rather than derived from {@link #getPath()}: the path answers "which route", and this has to answer "which
	 * button". One route serves several buttons.
	 * </p>
	 */
	String getAction();

	FailureReport setAction(String action);

	/**
	 * The {@code X-Trace-Id} of the failing response, or null when the failure never produced one.
	 *
	 * <p>
	 * The single most valuable column in the table. Everything else here is a description; this is an identifier.
	 * </p>
	 */
	String getTraceId();

	FailureReport setTraceId(String traceId);

	/** HTTP method of the failing request, as the client issued it. */
	String getHttpMethod();

	FailureReport setHttpMethod(String httpMethod);

	/** Path of the failing request, as the client issued it. */
	String getPath();

	FailureReport setPath(String path);

	/** HTTP status of the failing response, or null when there was none. */
	Integer getStatusCode();

	FailureReport setStatusCode(Integer statusCode);

	/**
	 * The message the client showed the user, kept verbatim.
	 *
	 * <p>
	 * Never interpreted anywhere. In the general case this is text the server produced from an exception, which may be influenced by the request that
	 * caused it.
	 * </p>
	 */
	String getErrorMessage();

	FailureReport setErrorMessage(String errorMessage);

	/** Where in the UI the user was standing - the client-side route, e.g. {@code "/detection"}. */
	String getRoute();

	FailureReport setRoute(String route);

	/**
	 * The reporter's user agent, stamped server-side from the request headers.
	 *
	 * <p>
	 * Not read from the request body on purpose: a client is free to lie about itself, and observed provenance is worth more than declared provenance.
	 * The request fields above are different - they describe an <i>earlier</i> request, which the server cannot observe at all.
	 * </p>
	 */
	String getUserAgent();

	FailureReport setUserAgent(String userAgent);

	/** What the user typed. The only prose in the table, and the reason the feature exists. */
	String getText();

	FailureReport setText(String text);

	/**
	 * {@code NEW}, {@code ACKNOWLEDGED} or {@code RESOLVED}. Use {@link #triageStatus()} for the parsed form.
	 *
	 * <p>
	 * Not called {@code status}: {@code AbstractCreatorEditorRestResponse} already owns that property name for the creator/editor audit block, so the
	 * column, this accessor and the DTO field all use the name the API can express. See V2.107.
	 * </p>
	 */
	String getTriageStatus();

	FailureReport setTriageStatus(String triageStatus);

	default FailureReportTriageStatus triageStatus() {
		return FailureReportTriageStatus.parse(getTriageStatus());
	}

	default FailureReport setTriageStatus(FailureReportTriageStatus status) {
		return setTriageStatus(status == null ? null : status.name());
	}
}
