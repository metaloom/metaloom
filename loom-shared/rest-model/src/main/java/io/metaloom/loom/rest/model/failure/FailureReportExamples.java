package io.metaloom.loom.rest.model.failure;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface FailureReportExamples extends ExampleValues {

	/** A plausible trace id: 32 hex characters, the width TraceIdHandler generates. */
	String EXAMPLE_TRACE_ID = "9f2c41ab7d0e4c6fa1b83e5d72c09148";

	default Example failureReportCreateRequestExample() {
		return new ExampleImpl(failureReportCreateRequest(), "A problem report submitted from the UI", HttpResponseStatus.CREATED);
	}

	default Example failureReportUpdateRequestExample() {
		return new ExampleImpl(failureReportUpdateRequest(), "Moving a report through triage", HttpResponseStatus.OK);
	}

	default Example failureReportResponseExample() {
		return new ExampleImpl(failureReportResponse(), "The problem report response", HttpResponseStatus.OK);
	}

	default Example failureReportListResponseExample() {
		return new ExampleImpl(failureReportListResponse(), "The problem report list response", HttpResponseStatus.OK);
	}

	default FailureReportResponse failureReportResponse() {
		FailureReportResponse model = new FailureReportResponse();
		model.setUuid(uuidC());
		model.setAction("createPerson");
		model.setTraceId(EXAMPLE_TRACE_ID);
		model.setHttpMethod("POST");
		model.setPath("/api/v1/persons");
		model.setStatusCode(500);
		model.setErrorMessage("Internal Server Error");
		model.setRoute("/detection");
		model.setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36");
		model.setText("I filled in the name and pressed Create. The dialog closed but the person is not in the list.");
		model.setTriageStatus("NEW");
		model.setHasScreenshot(true);
		model.setScreenshotUrl("https://loom.example.com/api/v1/failure-reports/" + uuidC() + "/screenshot");
		setCreatorEditor(model);
		return model;
	}

	default FailureReportListResponse failureReportListResponse() {
		FailureReportListResponse model = new FailureReportListResponse();
		model.setMetainfo(pagingInfo());
		model.add(failureReportResponse());
		model.add(failureReportResponse());
		return model;
	}

	default FailureReportCreateRequest failureReportCreateRequest() {
		FailureReportCreateRequest model = new FailureReportCreateRequest();
		model.setAction("createPerson");
		model.setTraceId(EXAMPLE_TRACE_ID);
		model.setHttpMethod("POST");
		model.setPath("/api/v1/persons");
		model.setStatusCode(500);
		model.setErrorMessage("Internal Server Error");
		model.setRoute("/detection");
		model.setText("I filled in the name and pressed Create. The dialog closed but the person is not in the list.");
		// Truncated in the example on purpose: a real one is hundreds of kilobytes, and an OpenAPI example that
		// carries a real PNG makes the spec unreadable in every viewer that renders it.
		model.setScreenshot("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
		model.setScreenshotWidth(2560);
		model.setScreenshotHeight(1440);
		return model;
	}

	default FailureReportUpdateRequest failureReportUpdateRequest() {
		FailureReportUpdateRequest model = new FailureReportUpdateRequest();
		model.setTriageStatus("ACKNOWLEDGED");
		return model;
	}
}
