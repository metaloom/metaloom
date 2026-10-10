import type { Person } from "../../types";
import type { PersonResponse } from "../../api/persons";

/**
 * Map a person from the REST model to the shape the UI renders.
 *
 * This lived in three places - the persons panel, the face detection view and the asset
 * detail view - which is how they came to disagree about what a person's avatar was.
 *
 * `avatarUrl` is taken straight from the response: the server decides how a person's
 * picture is addressed, and the UI only has to render it. It used to be composed here
 * from `primaryImageUuid` via `assetBinaryUrl`, which pointed at an asset - so a person
 * discovered in a video was illustrated with the whole video file.
 */
export function toUiPerson(r: PersonResponse, clusterIds: string[] = []): Person {
  return {
    id: r.uuid,
    name: [r.firstname, r.lastname].filter(Boolean).join(" ") || r.alias,
    description: r.alias,
    avatarUrl: r.avatarUrl ?? "",
    clusterIds,
    createdAt: r.status?.created ?? new Date().toISOString(),
  };
}

/**
 * Move clusters onto one person in the local person list.
 *
 * A cluster belongs to at most one person (`cluster.person_uuid` is scalar), so attributing it to
 * somebody also takes it off whoever held it before. The screen used to only add, which left a
 * re-assigned cluster listed under both people until the next reload. A person not in the list is
 * left alone - the caller decides whether a newly created one should be appended.
 */
export function moveClustersToPerson(persons: Person[], personId: string, clusterIds: string[]): Person[] {
  const moving = new Set(clusterIds);
  return persons.map(p => {
    if (p.id === personId) {
      return { ...p, clusterIds: [...new Set([...p.clusterIds, ...clusterIds])] };
    }
    return p.clusterIds.some(id => moving.has(id))
      ? { ...p, clusterIds: p.clusterIds.filter(id => !moving.has(id)) }
      : p;
  });
}
