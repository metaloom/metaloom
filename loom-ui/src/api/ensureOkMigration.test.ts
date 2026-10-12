import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, SESSION_EXPIRED_EVENT, TRACE_ID_HEADER } from "./http";
import { cancelChatStream, streamChatMessage } from "./agent";
import { deleteAnnotation } from "./annotations";
import { deleteAsset } from "./assets";
import { deleteAssetBinary, fetchAssetBinaryBlob } from "./binaries";
import { deleteBlacklist } from "./blacklist";
import { deleteChat } from "./chat";
import { deleteChatAttachment, fetchChatAttachmentBlob } from "./chatAttachments";
import { deleteChatSession } from "./chatSessions";
import { deleteComment } from "./comments";
import { deleteDedupGroup } from "./dedup";
import { deleteGroup } from "./groups";
import { deleteLibrary } from "./libraries";
import { deleteMemoryDenyRule } from "./memoryDenylist";
import { loadMetrics } from "./metrics";
import { fetchContentTypes, fetchNodeAvailability, fetchNodeDescriptor, fetchNodeDescriptors } from "./nodeDescriptors";
import { clearNotifications, deleteNotification, markAllNotificationsRead } from "./notifications";
import {
  cancelPipelineRun, continuePipelineRunBreakpoint, deletePipeline, pausePipelineRun,
  reExecutePipelineRunNode, resumePipelineRun, setPipelineRunBreakpoints, stepPipelineRun,
} from "./pipelines";
import { deletePool } from "./pools";
import { forgetProcessor } from "./processors";
import { deleteRemix } from "./remixes";
import { deleteRole } from "./roles";
import { deleteSkill } from "./skills";
import { deleteSpace } from "./spaces";
import { deleteTask, unassignTaskFromAsset, unassignTaskFromGroup, unassignTaskFromUser } from "./tasks";
import { deleteToken } from "./tokens";
import { deleteTranscript } from "./transcripts";
import { deleteUser } from "./users";

/**
 * Every call site that used to inline `if (!res.ok) throw new Error(...)` now goes through
 * `ensureOk`. Table-driven so a module that regresses to a plain `Error` (losing the status, the
 * trace id and the session-expired event) fails here by name.
 */

const T = "test-token";

const CASES: [string, () => Promise<unknown>][] = [
  ["agent.streamChatMessage", () => streamChatMessage(T, "c1", { message: "hi" } as never, () => {})],
  ["agent.cancelChatStream", () => cancelChatStream(T, "c1")],
  ["annotations.deleteAnnotation", () => deleteAnnotation(T, "u1")],
  ["assets.deleteAsset", () => deleteAsset(T, "u1")],
  ["binaries.fetchAssetBinaryBlob", () => fetchAssetBinaryBlob(T, "a1")],
  ["binaries.deleteAssetBinary", () => deleteAssetBinary(T, "a1")],
  ["blacklist.deleteBlacklist", () => deleteBlacklist(T, "u1")],
  ["chat.deleteChat", () => deleteChat(T, "u1")],
  ["chatAttachments.deleteChatAttachment", () => deleteChatAttachment(T, "c1", "a1")],
  ["chatAttachments.fetchChatAttachmentBlob", () => fetchChatAttachmentBlob(T, "c1", "a1")],
  ["chatSessions.deleteChatSession", () => deleteChatSession(T, "u1")],
  ["comments.deleteComment", () => deleteComment(T, "u1")],
  ["dedup.deleteDedupGroup", () => deleteDedupGroup(T, "u1")],
  ["groups.deleteGroup", () => deleteGroup(T, "u1")],
  ["libraries.deleteLibrary", () => deleteLibrary(T, "u1")],
  ["memoryDenylist.deleteMemoryDenyRule", () => deleteMemoryDenyRule(T, "u1")],
  ["metrics.loadMetrics", () => loadMetrics(T)],
  ["nodeDescriptors.fetchNodeDescriptors", () => fetchNodeDescriptors()],
  ["nodeDescriptors.fetchNodeAvailability", () => fetchNodeAvailability(T)],
  ["nodeDescriptors.fetchNodeDescriptor", () => fetchNodeDescriptor("facedetect")],
  ["nodeDescriptors.fetchContentTypes", () => fetchContentTypes()],
  ["notifications.markAllNotificationsRead", () => markAllNotificationsRead(T)],
  ["notifications.deleteNotification", () => deleteNotification(T, "u1")],
  ["notifications.clearNotifications", () => clearNotifications(T)],
  ["pipelines.deletePipeline", () => deletePipeline(T, "p1")],
  ["pipelines.cancelPipelineRun", () => cancelPipelineRun(T, "p1", "r1")],
  ["pipelines.pausePipelineRun", () => pausePipelineRun(T, "p1", "r1")],
  ["pipelines.resumePipelineRun", () => resumePipelineRun(T, "p1", "r1")],
  ["pipelines.setPipelineRunBreakpoints", () => setPipelineRunBreakpoints(T, "p1", "r1", ["n1"])],
  ["pipelines.continuePipelineRunBreakpoint", () => continuePipelineRunBreakpoint(T, "p1", "r1", "n1")],
  ["pipelines.reExecutePipelineRunNode", () => reExecutePipelineRunNode(T, "p1", "r1", "n1", "i1", 0)],
  ["pipelines.stepPipelineRun", () => stepPipelineRun(T, "p1", "r1")],
  ["pools.deletePool", () => deletePool(T, "u1")],
  ["processors.forgetProcessor", () => forgetProcessor(T, "node-1")],
  ["remixes.deleteRemix", () => deleteRemix(T, "u1")],
  ["roles.deleteRole", () => deleteRole(T, "u1")],
  ["skills.deleteSkill", () => deleteSkill(T, "u1")],
  ["spaces.deleteSpace", () => deleteSpace(T, "u1")],
  ["tasks.deleteTask", () => deleteTask(T, "u1")],
  ["tasks.unassignTaskFromAsset", () => unassignTaskFromAsset(T, "a1", "t1")],
  ["tasks.unassignTaskFromUser", () => unassignTaskFromUser(T, "t1", "u1")],
  ["tasks.unassignTaskFromGroup", () => unassignTaskFromGroup(T, "t1", "g1")],
  ["tokens.deleteToken", () => deleteToken(T, "u1")],
  ["transcripts.deleteTranscript", () => deleteTranscript(T, "a1", "t1")],
  ["users.deleteUser", () => deleteUser(T, "u1")],
];

/**
 * Calls that carry no credentials (the node-descriptor routes load before login). A 401 there says
 * nothing about the session, so it must not log a valid one out.
 */
const ANONYMOUS = new Set([
  "nodeDescriptors.fetchNodeDescriptors",
  "nodeDescriptors.fetchNodeDescriptor",
  "nodeDescriptors.fetchContentTypes",
]);

/** A fresh failing response per call: a body can be read only once. */
function stubFailure(status: number) {
  const fetchMock = vi.fn().mockImplementation(async () =>
    new Response(JSON.stringify({ message: `server said ${status}` }), {
      status,
      headers: { "Content-Type": "application/json", [TRACE_ID_HEADER]: "trace-abc" },
    })
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

/** `window` does not exist in the node test environment; `http.ts` only needs `dispatchEvent`. */
function stubWindow() {
  const target = new EventTarget();
  const listener = vi.fn();
  target.addEventListener(SESSION_EXPIRED_EVENT, listener);
  vi.stubGlobal("window", target);
  return listener;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("migrated API calls fail with ApiError", () => {
  it.each(CASES)("%s throws ApiError carrying the status and trace id", async (_name, call) => {
    const fetchMock = stubFailure(500);

    const error = await call().then(() => null, (e: unknown) => e);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(500);
    expect((error as ApiError).traceId).toBe("trace-abc");
    expect((error as ApiError).serverMessage).toBe("server said 500");
  });

  it.each(CASES.filter(([name]) => !ANONYMOUS.has(name)))("%s fires the session-expired event on a 401", async (_name, call) => {
    stubFailure(401);
    const listener = stubWindow();

    const error = await call().then(() => null, (e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it.each(CASES.filter(([name]) => ANONYMOUS.has(name)))("%s does not end the session on a 401: it sent no token", async (_name, call) => {
    stubFailure(401);
    const listener = stubWindow();

    const error = await call().then(() => null, (e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
    expect(listener).not.toHaveBeenCalled();
  });

  it("node availability without a token is anonymous too", async () => {
    stubFailure(401);
    const listener = stubWindow();

    await expect(fetchNodeAvailability(null)).rejects.toBeInstanceOf(ApiError);
    expect(listener).not.toHaveBeenCalled();
  });
});
