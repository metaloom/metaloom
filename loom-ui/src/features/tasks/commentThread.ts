import { CommentResponse } from "../../api/comments";

export interface CommentThread {
  root: CommentResponse;
  replies: CommentResponse[];
}

/**
 * Group a flat comment list into roots and their direct replies.
 *
 * The API returns comments flat, with `parentUuid` pointing at the comment being
 * replied to. Threading is rendered **one level deep only**: a reply to a reply is
 * re-parented onto the nearest root rather than nesting further. Arbitrary depth is a
 * separate design problem (indentation runs out, and the API has no depth limit), and
 * silently dropping a deep reply would lose a real comment.
 *
 * A reply whose parent is not in the list — the parent was deleted, or paging split the
 * thread — is promoted to a root so it stays visible.
 */
export function threadComments(comments: CommentResponse[]): CommentThread[] {
  const byUuid = new Map(comments.map((c) => [c.uuid, c]));

  /** Walk up to the top-most ancestor present in this list. */
  const rootOf = (comment: CommentResponse): CommentResponse => {
    const seen = new Set<string>([comment.uuid]);
    let current = comment;
    while (current.parentUuid) {
      const parent = byUuid.get(current.parentUuid);
      // Missing parent, or a cycle the server should never produce but which would
      // otherwise hang the render.
      if (!parent || seen.has(parent.uuid)) break;
      seen.add(parent.uuid);
      current = parent;
    }
    return current;
  };

  const threads: CommentThread[] = [];
  const indexByRoot = new Map<string, number>();

  // Roots first, in list order, so the thread order matches the comment order.
  for (const comment of comments) {
    if (rootOf(comment) === comment) {
      indexByRoot.set(comment.uuid, threads.length);
      threads.push({ root: comment, replies: [] });
    }
  }
  for (const comment of comments) {
    const root = rootOf(comment);
    if (root === comment) continue;
    const idx = indexByRoot.get(root.uuid);
    if (idx !== undefined) {
      threads[idx].replies.push(comment);
    } else {
      // The walk ended somewhere that is not itself a root — only reachable through a
      // parent cycle. Promote rather than drop: a malformed link must not make a real
      // comment disappear from the thread.
      indexByRoot.set(comment.uuid, threads.length);
      threads.push({ root: comment, replies: [] });
    }
  }
  return threads;
}
