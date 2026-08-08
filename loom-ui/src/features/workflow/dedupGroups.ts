import {
  ROLE_KEEP,
  STATUS_PENDING,
  updateDedupGroup,
  type DedupGroupMemberModel,
  type DedupGroupResponse,
} from "../../api/dedup";

/**
 * Pure logic for the Workflow "deduplication" mode.
 *
 * Extracted from the component so vitest can cover it: loom-ui has no jsdom and no React Testing
 * Library, so anything that needs a renderer has to be a mocked Playwright spec instead.
 */

/**
 * The member the group would keep.
 *
 * `keepAssetUuid` wins over `role === "KEEP"` because the server writes only the pointer when a
 * reviewer reassigns the keep — the member roles keep describing the machine's original choice.
 * Falls back to the KEEP member, then to the first member, so a malformed group still renders.
 */
export function keepMember(group: DedupGroupResponse): DedupGroupMemberModel | undefined {
  const members = group.members ?? [];
  if (group.keepAssetUuid) {
    const pointed = members.find(m => m.assetUuid === group.keepAssetUuid);
    if (pointed) {
      return pointed;
    }
  }
  return members.find(m => m.role === ROLE_KEEP) ?? members[0];
}

/** Every member that is not the keep — the files a confirmation would move aside. */
export function dupMembers(group: DedupGroupResponse): DedupGroupMemberModel[] {
  const keep = keepMember(group);
  return (group.members ?? []).filter(m => m.assetUuid !== keep?.assetUuid);
}

/**
 * Whether a member looked like a whole file at discovery time.
 *
 * An unknown (absent) `zeroChunkCount` counts as complete: not every ingest path measures it, and
 * treating "unmeasured" as "truncated" would flag most of the queue.
 */
export function isComplete(member: DedupGroupMemberModel): boolean {
  return member.zeroChunkCount === undefined || member.zeroChunkCount === 0;
}

/** Human-readable byte size. Sizes are what a reviewer overrides the machine's keep choice on. */
export function formatSize(bytes?: number): string {
  if (bytes === undefined || bytes === null || Number.isNaN(bytes)) {
    return "—";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

/** Replace one group in the queue, by uuid. Returns a new array; unknown uuids are left alone. */
export function replaceGroup(
  groups: DedupGroupResponse[],
  updated: DedupGroupResponse
): DedupGroupResponse[] {
  return groups.map(g => (g.uuid === updated.uuid ? updated : g));
}

/**
 * Record a decision on a group and return the server's version of it.
 *
 * The caller flips its chip optimistically and restores the previous group when this rejects — a
 * row that *looks* decided but was never written is the failure mode this whole screen exists to
 * avoid.
 */
export async function decideGroup(
  token: string,
  group: DedupGroupResponse,
  status: string
): Promise<DedupGroupResponse> {
  return updateDedupGroup(token, group.uuid, { status, keepAssetUuid: group.keepAssetUuid });
}

/**
 * Reassign which member the group keeps, without deciding it yet.
 *
 * The server requires a status on every PATCH, so an undecided group repeats PENDING rather than
 * accidentally confirming itself as a side effect of picking a different file.
 */
export async function reassignKeep(
  token: string,
  group: DedupGroupResponse,
  keepAssetUuid: string
): Promise<DedupGroupResponse> {
  return updateDedupGroup(token, group.uuid, {
    status: group.status ?? STATUS_PENDING,
    keepAssetUuid,
  });
}
