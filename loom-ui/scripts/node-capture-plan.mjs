// The map from a node documentation page to the node kind(s) it documents.
//
// Pages and kinds are not one-to-one, which is the whole reason this file exists rather than the
// capture scripts globbing the docs directory:
//
//   - `hash/` documents four kinds (md5, sha256, sha512, chunk-hash) that differ only in algorithm;
//   - `dedup/` documents three (hash-dedup and the two fingerprint kinds);
//   - `loom-fetch` is a real kind with no page of its own.
//
// `kind` is the one a picture is taken of; `alsoKinds` are the others the page covers, and exist
// here so the guard can prove that every shipped kind is documented *somewhere* rather than only
// that every page has a picture. A node added to the product with no page at all is the failure
// mode that check is for, and it is not visible from the docs tree.
//
// Ordered as the docs sidebar orders them (alphabetically by directory), so a capture run reads in
// the same order a reader browses.

/**
 * `view: "run-detail"` shoots the node detail sidebar's Results tab instead of the node card. Only
 * `dedup` needs it, and for a structural reason: its kinds declare no output ports at all, so
 * `NodeResultStrip` renders nothing and the card is a title with an empty body. The Results tab at
 * least states that the node ran, how long it took and that it produced nothing — which is the node's
 * actual shape, not a gap in the picture.
 *
 * @type {{page: string, kind: string, alsoKinds?: string[], nodeData?: object, view?: "card" | "run-detail"}[]}
 */
export const PAGES = [
  { page: "captioning", kind: "captioning" },
  { page: "consistency", kind: "consistency" },
  { page: "dedup", kind: "hash-dedup", alsoKinds: ["fingerprint-dedup", "fingerprint-dedup-apply"], view: "run-detail" },
  { page: "depthmap", kind: "depthmap" },
  { page: "dominant-color", kind: "dominant-color" },
  { page: "facedescription", kind: "facedescription" },
  { page: "facedetect", kind: "facedetect" },
  { page: "filesystem-source", kind: "filesystem-source" },
  // The only PORT_LIST parameter in the product. Seeded with real buckets because an empty bucket
  // editor is a single "Add bucket" stub — a picture of the control before it does anything, on the
  // one page whose entire subject is what the buckets do.
  {
    page: "filter", kind: "filter",
    nodeData: {
      filterBy: "LANGUAGE",
      buckets: [
        { id: "de", label: "German", match: "German" },
        { id: "en", label: "English", match: "English" },
      ],
    },
  },
  { page: "fingerprint", kind: "fingerprint" },
  { page: "gdrive-source", kind: "gdrive-source" },
  { page: "guard", kind: "guard" },
  { page: "hash", kind: "sha512", alsoKinds: ["md5", "sha256", "chunk-hash"] },
  { page: "image-manipulation", kind: "image-manipulation" },
  { page: "imagegen", kind: "imagegen" },
  { page: "llm", kind: "llm" },
  { page: "metadata", kind: "metadata" },
  { page: "objectdetect", kind: "objectdetect" },
  { page: "ocr", kind: "ocr" },
  { page: "onedrive-source", kind: "onedrive-source" },
  { page: "quality", kind: "quality" },
  { page: "sam2", kind: "sam2" },
  { page: "s3-sink", kind: "s3-sink" },
  { page: "s3-source", kind: "s3-source" },
  { page: "scene-detection", kind: "scene-detection" },
  { page: "scene-layout", kind: "scene-layout" },
  { page: "script", kind: "script" },
  { page: "sentiment", kind: "sentiment" },
  { page: "tag", kind: "tag" },
  { page: "thumbnail", kind: "thumbnail" },
  { page: "tika", kind: "tika" },
  { page: "translate", kind: "translate" },
  { page: "tts", kind: "tts" },
  { page: "videogen", kind: "videogen" },
  { page: "vlm", kind: "vlm" },
  { page: "watermark", kind: "watermark" },
  { page: "whisper", kind: "whisper" },
];

/**
 * Kinds that ship without a documentation page, deliberately.
 *
 * Anything not listed here and not reachable through `PAGES` is an undocumented node, which the
 * guard treats as an error. Keep the reason with the entry — an empty allowlist entry is how a
 * genuine gap gets normalised into "that has always been like that".
 */
export const UNDOCUMENTED_KINDS = {
  "loom-fetch": "an internal source used by the engine to re-read an asset; not composed by hand",
};

/** Every kind any page covers. */
export function documentedKinds() {
  const kinds = new Set();
  for (const entry of PAGES) {
    kinds.add(entry.kind);
    (entry.alsoKinds ?? []).forEach(k => kinds.add(k));
  }
  return kinds;
}
