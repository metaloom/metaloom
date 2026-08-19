import { describe, expect, it } from "vitest";
import { ApiError } from "../api/http";
import { failureSummary, toFailure } from "./failure";

const TRACE = "9f2c41ab7d0e4c6fa1b83e5d72c09148";

describe("toFailure", () => {
  it("keeps everything an ApiError carries", () => {
    const failure = toFailure(
      "createPerson",
      new ApiError("Internal Server Error", {
        status: 500,
        traceId: TRACE,
        serverMessage: "Internal Server Error",
        context: { action: "createPerson", method: "POST", path: "/api/v1/persons" },
      }),
      "/detection",
    );

    expect(failure).toMatchObject({
      action: "createPerson",
      traceId: TRACE,
      status: 500,
      method: "POST",
      path: "/api/v1/persons",
      route: "/detection",
    });
  });

  it("reads a typed per-module error duck-typed", () => {
    // UserApiError, SearchApiError and the rest are structurally like an ApiError without being
    // one. Importing six classes here would be worse than reading the two fields that matter.
    class UserApiError extends Error {
      status = 403;
      traceId = TRACE;
    }
    const failure = toFailure("updateUser", new UserApiError("nope"));
    expect(failure.status).toBe(403);
    expect(failure.traceId).toBe(TRACE);
  });

  it("still produces a reportable failure for a value that is not an Error at all", () => {
    // A report about an unclassifiable error is worth more than no report.
    const failure = toFailure("deleteTag", undefined);
    expect(failure.action).toBe("deleteTag");
    expect(failure.message).toBeTruthy();
    expect(failure.status).toBeUndefined();
  });

  it("replaces machine output with something a person can read", () => {
    expect(toFailure("loadLibraries", new TypeError("Failed to fetch")).message).toBe(
      "The server could not be reached.",
    );
    expect(toFailure("createTag", new ApiError("API error 500: ", { status: 500 })).message).toBe(
      "The server failed to handle the request.",
    );
  });

  it("keeps a server message that already reads as a sentence", () => {
    const failure = toFailure(
      "createBlacklistEntry",
      new ApiError("The resource already exists.", { status: 409, serverMessage: "The resource already exists." }),
    );
    expect(failure.message).toBe("The resource already exists.");
  });

  it("words the common statuses so the user knows whose problem it is", () => {
    const wording = (status: number) => toFailure("x", new ApiError("API error " + status, { status })).message;
    expect(wording(403)).toBe("You do not have permission to do that.");
    expect(wording(404)).toBe("That no longer exists.");
    expect(wording(413)).toBe("That was too large to accept.");
  });
});

describe("failureSummary", () => {
  it("puts the request and the status on one line", () => {
    expect(
      failureSummary({ action: "createPerson", message: "x", method: "POST", path: "/api/v1/persons", status: 500 }),
    ).toBe("POST /api/v1/persons · HTTP 500");
  });

  it("is empty for a failure that never reached the server", () => {
    // A render throw has no request to describe, and an empty summary renders as nothing rather
    // than as a stray separator.
    expect(failureSummary({ action: "render:/assets", message: "x" })).toBe("");
  });
});
