import { describe, expect, it } from "vitest";
import { toChatMessage, toChatReference } from "./chat";

describe("toChatReference", () => {
  it("maps backend uuid to id", () => {
    expect(toChatReference({ type: "asset", uuid: "a1", label: "beach.mp4" })).toEqual({
      type: "asset",
      id: "a1",
      label: "beach.mp4",
    });
  });

  it("tolerates legacy UI-shaped references", () => {
    expect(toChatReference({ type: "task", id: "t1", label: "Review" })).toEqual({
      type: "task",
      id: "t1",
      label: "Review",
    });
  });
});

describe("toChatMessage", () => {
  it("maps a full backend assistant message", () => {
    const msg = toChatMessage({
      id: "m1",
      role: "assistant",
      content: "I found **beach.mp4**.",
      reasoning: "The user wants beach videos.",
      toolCalls: [{ id: "c1", name: "search_assets", resultSummary: "1 asset", isError: false, durationMs: 12 }],
      references: [{ type: "asset", uuid: "a1", label: "beach.mp4" }],
      createdAt: "2026-07-22T10:15:03Z",
    });
    expect(msg.role).toBe("assistant");
    expect(msg.reasoning).toBe("The user wants beach videos.");
    expect(msg.toolCalls).toHaveLength(1);
    expect(msg.references).toEqual([{ type: "asset", id: "a1", label: "beach.mp4" }]);
  });

  it("omits empty optional fields", () => {
    const msg = toChatMessage({ id: "m2", role: "user", content: "Hi", createdAt: "2026-07-22T10:15:03Z" });
    expect(msg.reasoning).toBeUndefined();
    expect(msg.toolCalls).toBeUndefined();
    expect(msg.references).toBeUndefined();
  });

  it("fills defaults for missing id/createdAt", () => {
    const msg = toChatMessage({ role: "assistant", content: "x" });
    expect(msg.id).toBeTruthy();
    expect(msg.createdAt).toBeTruthy();
  });
});
