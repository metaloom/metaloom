import type { HeldExecution, PipelineNodeTaskRecord } from "../../api/pipelines";

/**
 * Attempts at one node execution — the bookkeeping behind re-executing a held node.
 *
 * A node stopped at a breakpoint can be run again with different settings, and each attempt keeps
 * its own task record. So `(nodeId, elementSeq)` stops identifying a single record, and anything
 * that used to assume it did has to choose a generation. Choosing wrong is silent: the canvas would
 * show the result of an attempt the operator has already replaced.
 */

/** The attempts present in a set of task records, oldest first. */
export function generationsOf(tasks: PipelineNodeTaskRecord[] | undefined): number[] {
  if (!tasks || tasks.length === 0) return [];
  return [...new Set(tasks.map(task => task.generation ?? 0))].sort((a, b) => a - b);
}

/** The most recent attempt, which is what "the result" means everywhere by default. */
export function latestGeneration(tasks: PipelineNodeTaskRecord[] | undefined): number {
  const generations = generationsOf(tasks);
  return generations.length === 0 ? 0 : generations[generations.length - 1];
}

/**
 * Narrow a node's records to one attempt.
 *
 * A node downstream of a fan-out keeps one record per element within the attempt, so this filters
 * rather than picking — collapsing to a single record would hide which element of a fan-out failed.
 */
export function tasksForGeneration(
  tasks: PipelineNodeTaskRecord[] | undefined,
  generation: number,
): PipelineNodeTaskRecord[] {
  if (!tasks) return [];
  return tasks.filter(task => (task.generation ?? 0) === generation);
}

/**
 * Keep every node's records to a single attempt, defaulting to its latest.
 *
 * @param selected per-node generation the operator pinned, for comparing an attempt with the one
 *                 before it; a node absent from this map shows its most recent attempt
 */
export function pinGenerations(
  tasksByNode: Record<string, PipelineNodeTaskRecord[]>,
  selected: Record<string, number>,
): Record<string, PipelineNodeTaskRecord[]> {
  const pinned: Record<string, PipelineNodeTaskRecord[]> = {};
  for (const [nodeId, tasks] of Object.entries(tasksByNode)) {
    const generation = selected[nodeId] ?? latestGeneration(tasks);
    const forGeneration = tasksForGeneration(tasks, generation);
    // A pinned generation that no longer exists — the operator switched run item — falls back to
    // everything rather than showing an empty node.
    pinned[nodeId] = forGeneration.length > 0 ? forGeneration : tasksForGeneration(tasks, latestGeneration(tasks));
  }
  return pinned;
}

/**
 * Which element of a node is held for the item being inspected, if any.
 *
 * Re-execution needs the element, not just the node: a node downstream of a fan-out has one
 * execution per element and only some of them may be held.
 *
 * @returns the element sequence, or null when this node is not holding anything for that item
 */
export function heldElementSeq(
  held: HeldExecution[] | undefined,
  nodeId: string,
  itemUuid: string | null | undefined,
): number | null {
  if (!held || !itemUuid) return null;
  const match = held.find(entry => entry.nodeId === nodeId && entry.itemUuid === itemUuid);
  return match ? match.elementSeq : null;
}

/**
 * Fold a run-scoped draft over the settings a node is defined with.
 *
 * The draft is what the operator typed while the node sat at a breakpoint. It is deliberately not
 * written into the definition — until they press "Save to pipeline" — so this is the only place the
 * two are combined, and the form reads through it.
 */
export function effectiveOptions(
  definitionOptions: Record<string, unknown>,
  draft: Record<string, unknown> | undefined,
): Record<string, unknown> {
  return draft && Object.keys(draft).length > 0 ? { ...definitionOptions, ...draft } : definitionOptions;
}
