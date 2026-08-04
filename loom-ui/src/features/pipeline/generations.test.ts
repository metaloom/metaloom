import { describe, expect, it } from "vitest";

import {
  effectiveOptions,
  generationsOf,
  heldElementSeq,
  latestGeneration,
  pinGenerations,
  tasksForGeneration,
} from "./generations";
import type { HeldExecution, PipelineNodeTaskRecord } from "../../api/pipelines";

function task(nodeId: string, generation: number, elementSeq = 0, state = "DONE"): PipelineNodeTaskRecord {
  return {
    uuid: `${nodeId}-${generation}-${elementSeq}`,
    itemUuid: "item-1",
    runUuid: "run-1",
    nodeId,
    nodeKind: nodeId,
    elementSeq,
    generation,
    state,
    attempt: 1,
    maxAttempts: 1,
  };
}

describe("generationsOf", () => {
  it("lists the attempts present, oldest first and without duplicates", () => {
    // A node downstream of a fan-out has one record per element within each attempt, so the same
    // generation appears several times and must still count once.
    expect(generationsOf([task("f", 1, 0), task("f", 0, 0), task("f", 1, 1)])).toEqual([0, 1]);
  });

  it("treats a record with no generation as the original run", () => {
    // Rows written before the column existed, and every task an ordinary run produces.
    const legacy = { ...task("f", 0), generation: undefined as unknown as number };
    expect(generationsOf([legacy])).toEqual([0]);
  });

  it("has no attempts when there are no records", () => {
    expect(generationsOf([])).toEqual([]);
    expect(generationsOf(undefined)).toEqual([]);
    expect(latestGeneration(undefined)).toBe(0);
  });
});

describe("tasksForGeneration", () => {
  it("keeps every element of one attempt", () => {
    const tasks = [task("f", 0, 0), task("f", 1, 0), task("f", 1, 1)];
    expect(tasksForGeneration(tasks, 1).map(t => t.elementSeq)).toEqual([0, 1]);
    expect(tasksForGeneration(tasks, 0)).toHaveLength(1);
  });
});

describe("pinGenerations", () => {
  it("shows each node's most recent attempt by default", () => {
    // The default that matters: after a re-execution the canvas must show what the node just
    // produced, not the attempt it was compared against.
    const pinned = pinGenerations({ faces: [task("faces", 0), task("faces", 1)] }, {});
    expect(pinned.faces.map(t => t.generation)).toEqual([1]);
  });

  it("shows an earlier attempt when one is pinned", () => {
    const pinned = pinGenerations({ faces: [task("faces", 0), task("faces", 1)] }, { faces: 0 });
    expect(pinned.faces.map(t => t.generation)).toEqual([0]);
  });

  it("falls back to the latest when the pinned attempt does not exist here", () => {
    // Switching the inspected run item keeps the pin but changes the records. Showing nothing
    // would read as "this node did not run".
    const pinned = pinGenerations({ faces: [task("faces", 0)] }, { faces: 3 });
    expect(pinned.faces.map(t => t.generation)).toEqual([0]);
  });

  it("pins each node independently", () => {
    const pinned = pinGenerations(
      { faces: [task("faces", 0), task("faces", 1)], thumb: [task("thumb", 0)] },
      { faces: 0 },
    );
    expect(pinned.faces[0].generation).toBe(0);
    expect(pinned.thumb[0].generation).toBe(0);
  });
});

describe("heldElementSeq", () => {
  const held: HeldExecution[] = [
    { nodeId: "faces", itemUuid: "item-1", elementSeq: 0 },
    { nodeId: "describe", itemUuid: "item-1", elementSeq: 2 },
  ];

  it("finds which element of a node is held for the inspected item", () => {
    expect(heldElementSeq(held, "faces", "item-1")).toBe(0);
    expect(heldElementSeq(held, "describe", "item-1")).toBe(2);
  });

  it("is null for a node that is not holding, or for another item", () => {
    // Element 0 is a real answer and null is not — returning 0 for "not held" would offer
    // re-execution on a node the engine would refuse.
    expect(heldElementSeq(held, "thumb", "item-1")).toBeNull();
    expect(heldElementSeq(held, "faces", "item-2")).toBeNull();
    expect(heldElementSeq(held, "faces", null)).toBeNull();
    expect(heldElementSeq(undefined, "faces", "item-1")).toBeNull();
  });
});

describe("effectiveOptions", () => {
  it("lays the draft over the pipeline's own settings", () => {
    expect(effectiveOptions({ cols: 6, rows: 1 }, { cols: 4 })).toEqual({ cols: 4, rows: 1 });
  });

  it("returns the definition untouched when there is no draft", () => {
    const definition = { cols: 6 };
    expect(effectiveOptions(definition, undefined)).toBe(definition);
    expect(effectiveOptions(definition, {})).toBe(definition);
  });

  it("does not mutate the definition", () => {
    const definition = { cols: 6 };
    effectiveOptions(definition, { cols: 4 });
    expect(definition.cols).toBe(6);
  });
});
