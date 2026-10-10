import { describe, expect, it } from "vitest";
import type { Person } from "../../types";
import { moveClustersToPerson, toUiPerson } from "./personMapping";

function person(id: string, clusterIds: string[]): Person {
  return { id, name: id, description: id, avatarUrl: "", clusterIds, createdAt: "2026-01-01T00:00:00Z" };
}

describe("moveClustersToPerson", () => {
  it("adds the clusters to the target and takes them off everyone else", () => {
    const next = moveClustersToPerson([person("anna", ["c1", "c2"]), person("bob", ["c3"])], "bob", ["c1"]);
    expect(next.find(p => p.id === "anna")!.clusterIds).toEqual(["c2"]);
    expect(next.find(p => p.id === "bob")!.clusterIds).toEqual(["c3", "c1"]);
  });

  it("does not duplicate a cluster the target already holds", () => {
    const next = moveClustersToPerson([person("bob", ["c1"])], "bob", ["c1", "c2"]);
    expect(next[0].clusterIds).toEqual(["c1", "c2"]);
  });

  it("returns untouched persons by identity, so unrelated cards do not re-render", () => {
    const carl = person("carl", ["c9"]);
    const next = moveClustersToPerson([carl, person("bob", [])], "bob", ["c1"]);
    expect(next[0]).toBe(carl);
  });

  it("detaches from the previous holder even when the target is not in the list yet", () => {
    const next = moveClustersToPerson([person("anna", ["c1"])], "new-person", ["c1"]);
    expect(next).toHaveLength(1);
    expect(next[0].clusterIds).toEqual([]);
  });
});

describe("toUiPerson", () => {
  it("names a person by first and last name, falling back to the alias", () => {
    expect(toUiPerson({ uuid: "p", alias: "jack", firstname: "Jack", lastname: "O'Neill" } as never).name).toBe("Jack O'Neill");
    expect(toUiPerson({ uuid: "p", alias: "jack" } as never).name).toBe("jack");
  });
});
