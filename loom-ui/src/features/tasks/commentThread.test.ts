import { describe, expect, it } from "vitest";
import { threadComments } from "./commentThread";
import { CommentResponse } from "../../api/comments";

const c = (uuid: string, parentUuid?: string): CommentResponse => ({ uuid, text: uuid, parentUuid });

describe("threadComments", () => {
  it("returns every comment as a root when nothing is a reply", () => {
    const threads = threadComments([c("a"), c("b")]);
    expect(threads.map((t) => t.root.uuid)).toEqual(["a", "b"]);
    expect(threads.every((t) => t.replies.length === 0)).toBe(true);
  });

  it("nests replies under their root and preserves list order", () => {
    const threads = threadComments([c("a"), c("b"), c("a1", "a"), c("a2", "a"), c("b1", "b")]);

    expect(threads.map((t) => t.root.uuid)).toEqual(["a", "b"]);
    expect(threads[0].replies.map((r) => r.uuid)).toEqual(["a1", "a2"]);
    expect(threads[1].replies.map((r) => r.uuid)).toEqual(["b1"]);
  });

  it("flattens a reply-to-a-reply onto the nearest root rather than nesting deeper", () => {
    // Rendering is one level deep, but a deep reply must not vanish.
    const threads = threadComments([c("a"), c("a1", "a"), c("a1a", "a1")]);

    expect(threads).toHaveLength(1);
    expect(threads[0].replies.map((r) => r.uuid)).toEqual(["a1", "a1a"]);
  });

  it("promotes an orphaned reply to a root so it stays visible", () => {
    // The parent was deleted, or paging split the thread.
    const threads = threadComments([c("orphan", "gone")]);

    expect(threads.map((t) => t.root.uuid)).toEqual(["orphan"]);
    expect(threads[0].replies).toEqual([]);
  });

  it("does not hang on a parent cycle", () => {
    // The server should never produce this, but an infinite walk would blank the app.
    const threads = threadComments([c("x", "y"), c("y", "x")]);
    expect(threads.length).toBeGreaterThan(0);
  });

  it("handles an empty list", () => {
    expect(threadComments([])).toEqual([]);
  });
});
