import type { NodeAvailability, NodeAvailabilityMap, NodeDescriptor } from "../../types/nodeDescriptors";

/**
 * The one place that decides which nodes a picker shows, and in what order.
 *
 * ## Why this is a module and not three inline expressions
 *
 * The add-node search bar and the `N`-key command palette are separate components, and the same
 * filter used to be written out **three** times inside `PipelineEditor.tsx`: once for the command
 * palette's list, and twice more for the search bar — once in its `onKeyDown` handler and again in
 * the rendered list.
 *
 * The last two are indexed by the same `addNodeIdx`. They agreed only because the expressions were
 * character-identical. The moment one of them sorted differently — which is exactly what availability
 * ordering does — `↑`/`↓` would move the highlight through one ordering while `Enter` picked
 * `filtered[addNodeIdx]` out of another. The user watches the highlight land on *Whisper*, presses
 * Enter, and gets *Dedup*. Nothing throws and nothing is logged.
 *
 * So: one function, one ordering, consumed by both.
 */

export interface PickerOptions {
  /** Free-text query, matched against name, node id and category. */
  query?: string;
  /** Whether nodes with no online worker are listed at all. */
  showOffline?: boolean;
  /** Fleet state; absent means "no fleet information", which reads as available. */
  availability?: NodeAvailabilityMap;
}

export interface PickerEntry {
  descriptor: NodeDescriptor;
  /** Whether a worker offering this node is online right now. */
  available: boolean;
  /** The fleet state behind {@link available}, when the server sent any. */
  state?: NodeAvailability;
}

/**
 * Whether a node can currently run.
 *
 * Unknown means **available**. The checked-in descriptor snapshot the offline website editor reads
 * carries no availability block at all, and a node the server said nothing about must not silently
 * become unusable — the failure mode of guessing "unavailable" is an empty palette with no
 * explanation, which reads as a broken UI rather than a stopped fleet.
 */
export function isNodeAvailable(nodeId: string, availability?: NodeAvailabilityMap): boolean {
  if (!availability) return true;
  const state = availability[nodeId];
  return state ? state.available : true;
}

/** The node type id, tolerating a server still on the pre-rename `kind`. */
export function nodeIdOf(descriptor: NodeDescriptor): string {
  return descriptor.nodeId ?? descriptor.kind;
}

function matchesQuery(descriptor: NodeDescriptor, query: string): boolean {
  if (!query) return true;
  const needle = query.toLowerCase();
  return (
    descriptor.name.toLowerCase().includes(needle) ||
    nodeIdOf(descriptor).toLowerCase().includes(needle) ||
    descriptor.category.toLowerCase().includes(needle)
  );
}

/**
 * Filter and order the nodes a picker offers.
 *
 * Available nodes come first, each group keeping the order the server sent — which is category
 * grouping the backend already applies. Offline nodes fall to the bottom rather than disappearing,
 * unless `showOffline` is explicitly false.
 *
 * **This orders the picker only.** It must never be used to filter the canvas: a node already on a
 * saved graph has to render with its ports whether or not a worker is up, because a node with no
 * ports drops every edge attached to it.
 */
export function selectPickerNodes(
  descriptors: NodeDescriptor[],
  options: PickerOptions = {},
): PickerEntry[] {
  const { query = "", showOffline = true, availability } = options;

  const entries: PickerEntry[] = (descriptors ?? [])
    .filter((descriptor) => matchesQuery(descriptor, query))
    .map((descriptor) => ({
      descriptor,
      available: isNodeAvailable(nodeIdOf(descriptor), availability),
      state: availability?.[nodeIdOf(descriptor)],
    }));

  const visible = showOffline ? entries : entries.filter((entry) => entry.available);

  // Stable partition rather than a comparator: sort() is stable in every engine we target, but
  // saying "available ones, then the rest" directly is harder to get subtly wrong than a comparator
  // that also has to preserve the server's category ordering within each group.
  return [...visible.filter((e) => e.available), ...visible.filter((e) => !e.available)];
}

/**
 * How many nodes the `showOffline` toggle is currently hiding.
 *
 * Shown next to the toggle: hiding entries without saying so turns "my node is missing" into a
 * support question.
 */
export function hiddenOfflineCount(
  descriptors: NodeDescriptor[],
  options: PickerOptions = {},
): number {
  if (options.showOffline !== false) return 0;
  return selectPickerNodes(descriptors, { ...options, showOffline: true }).filter((e) => !e.available).length;
}

/**
 * The caption explaining why a node cannot be added right now.
 *
 * `providedBy` is only served to a caller with `READ_CORTEX_INSTANCE`, and the palette loads before
 * anyone has logged in — so the message has to still say something useful without it.
 */
export function offlineReason(state: NodeAvailability | undefined): string | undefined {
  if (!state || state.available) return undefined;
  if (state.providedBy && state.providedBy.length > 0) {
    return `offline — last provided by ${state.providedBy.join(", ")}`;
  }
  return "no worker currently offers this node";
}
