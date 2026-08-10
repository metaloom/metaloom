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
