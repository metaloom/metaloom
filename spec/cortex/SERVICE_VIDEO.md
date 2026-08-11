# `loom/services/video` — Video Service Module

> ## 🔴 EMPTY STUB — DO NOT TREAT AS A SERVICE
>
> `loom/services/video` is a Maven module containing **exactly one Java file**, a
> **marker interface with no methods** ([`VideoAsset`](../../../loom/services/video/src/main/java/io/metaloom/loom/video/VideoAsset.java)),
> whose only body is the commented-out line `//String altText`.
>
> It has **no implementations, no Dagger module, no options class, no environment
> variables, no tests, and — verified by repo-wide grep — zero consumers.**
> No production or test code anywhere in the monorepo imports `io.metaloom.loom.video`.
>
> **All real video processing lives in Cortex nodes, not here.** See §2 for where to go instead.

This file exists so an agent asked to "work on the Loom video service" does not waste time
looking for a service that was never written, and knows which module to actually touch.

---

## 1. What the module actually is

| Fact | Value | Evidence |
|---|---|---|
| Maven coordinates | `io.metaloom.loom.service:loom-service-video:1.0.0-SNAPSHOT` | [`pom.xml`](../../../loom/services/video/pom.xml) |
| Maven name | `MetaLoom // Loom :: Service :: Video` | same |
| Declared dependencies of its own | **none** (the `<project>` body is parent + artifactId + name + `sourceEncoding`) | same |
| Inherited dependencies | `loom-api`, `loom-db-api`, `dagger` — from [`loom/services/pom.xml`](../../../loom/services/pom.xml) `<dependencies>`, applied to *every* service module | services parent pom |
| Registered as a module | yes — `<module>video</module>` in `loom/services/pom.xml` | services parent pom |
| Declared in dependencyManagement | yes — `loom/pom.xml` (aggregator convenience only) | `loom/pom.xml` |
| Actually depended on by anything | **no** — no `<dependency>` on `loom-service-video` exists in any pom | `rg "loom-service-video" --glob "*.xml"` returns only its own pom + the dependencyManagement entry |
| Java sources | 1 | `loom/services/video/src/main/java/io/metaloom/loom/video/VideoAsset.java` |
| Java tests | **0** — there is no `src/test` directory | `find loom/services/video -type f` |
| `README.md` | present, contains only the heading `# Loom - Video Service` | [`README.md`](../reports/README.md) |
| Last touched | `4c9e9326` "Restructure maven artifacts" (2026-04-03); created in `47e74fa3` "Initialize monorepo" (2026-04-01) — never modified since | `git log -- loom/services/video` |

It is the twin of [`loom/services/image`](../../../loom/services/image), which is the same
kind of stub (`MediaAsset` interface with one `altText()` method + an `ImageAsset` that
returns `null`) and likewise has zero consumers. Notably `VideoAsset` does **not**
`extends MediaAsset` — the two stubs are unrelated types; the commented-out `//String altText`
is the only hint that they were meant to converge.

### Architecture (as built)

```
loom/services/                          consumers
├── api, auth, rest, grpc, graphql,     ─── wired into loom/core via Dagger
│   mcp, fs, s3, tika, lucene, ...
├── image/  ImageAsset, MediaAsset      ─── ✗ nothing imports io.metaloom.loom.image
└── video/  VideoAsset  ◀── THIS SPEC   ─── ✗ nothing imports io.metaloom.loom.video
                │
                └── (dead end: no impl, no binding, no caller)

Real video work happens entirely outside this module:

  cortex/nodes/thumbnail        ─┐
  cortex/nodes/quality           │  video4j (OpenCV 5.1 native)
  cortex/nodes/scene-detection   ├─▶  ──────────────────────────▶ Loom REST
  cortex/nodes/fingerprint       │                                (json/segment
  cortex/nodes/captioning       ─┘                                 comps + binaries)
  cortex/nodes/facedetect        ── video4j-facedetect-*

  loom/services/lucene           ── video4j-fingerprint-indexer (HNSW similarity index)
  loom/services/rest             ── video4j-fingerprint (pom dependency; see §5 gotcha)
```

---

## 2. Where the work you probably meant to do actually lives

| If you were asked to … | Go to | Spec |
|---|---|---|
| Extract video thumbnails | `cortex/nodes/thumbnail/core` (`ThumbnailNode`) | [NODES.md](../features/nodes/NODES.md) |
| Compute video quality metrics (blur, fps, frame count) | `cortex/nodes/quality/core` (`QualityNode`) | [NODES.md](../features/nodes/NODES.md) |
| Detect scenes / shot boundaries | `cortex/nodes/scene-detection/core` | [NODES.md](../features/nodes/NODES.md), [NODE_SCENE_LAYOUT.md](../features/nodes/scene-layout/NODE_SCENE_LAYOUT.md) |
| Perceptual video fingerprints / near-duplicate detection | `cortex/nodes/fingerprint/core` + `loom/services/lucene` | [SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md), [NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) |
| Caption a video with a VLM | `cortex/nodes/captioning/core` | [NODE_VIDEO_CAPTIONING.md](../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md), [NODE_VIDEO_CAPTIONING_REPORT.md](../concept/NODE_VIDEO_CAPTIONING_REPORT.md) |
| Face detection in video | `cortex/nodes/facedetect/core` | [NODES.md](../features/nodes/NODES.md) |
| Apply a watermark to an image or video | `cortex/nodes/watermark/core` | [NODE_WATERMARK.md](../features/nodes/watermark/NODE_WATERMARK.md) |
| Store/serve video bytes | `loom/services/rest` binary routes | [../rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md) |
| Persist node output for a video asset | Loom REST comp endpoints | [NODES.md](../features/nodes/NODES.md) §persistence, [../../loom/RESTAPI.md](../loom/RESTAPI.md) |
| Model a video asset in the DB | `asset*` tables, not a Java `VideoAsset` | [../../loom/DOMAIN.md](../loom/DOMAIN.md), [../../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) |

---

## 3. Key Classes Reference

| Class | Package / path | Purpose |
|---|---|---|
| `VideoAsset` | `io.metaloom.loom.video` — [`loom/services/video/src/main/java/io/metaloom/loom/video/VideoAsset.java`](../../../loom/services/video/src/main/java/io/metaloom/loom/video/VideoAsset.java) | Empty marker interface. **No methods, no implementors, no callers.** The entire module. |
| `MediaAsset` | `io.metaloom.loom.image` — `loom/services/image/.../MediaAsset.java` | Sibling stub: `String altText()`. Referenced here only because `VideoAsset`'s comment suggests it was the intended supertype. Also unused. |
| `ImageAsset` | `io.metaloom.loom.image` — `loom/services/image/.../ImageAsset.java` | Sibling stub implementing `MediaAsset`, `altText()` returns `null`. Also unused. |

There is no `VideoService`, `VideoServiceImpl`, `VideoModule` (Dagger), `VideoOptions`, or
`VideoAssetImpl` anywhere in the repo — verified by grep, not assumed.

---

## 4. Environment Variables

**This module reads none.** It has no options class, no `@Inject`ed configuration, no
`System.getenv` / `System.getProperty` call — it contains no executable code at all.

For Loom-wide configuration see [../../loom/CONFIGURATION.md](../../loom/CONFIGURATION.md).
For the video-processing env vars that *do* exist (all Cortex-side, e.g. `OPENCV_LIB_DIR`,
`LD_LIBRARY_PATH=/opt/opencv/lib`, per-node options) see
[../../cortex/CONFIGURATION.md](../../cortex/CONFIGURATION.md) and
[../../cortex/BUILD.md](../../cortex/BUILD.md).

---

## 5. Native library dependencies

**This module has none — and that is the important part.**

`loom-service-video` does **not** depend on `video4j`, OpenCV, FFmpeg, or any native
library. Building or loading it cannot produce an `UnsatisfiedLinkError`. It is pure,
inert Java.

The native coupling that agents keep tripping over lives elsewhere and is documented in
[../../cortex/BUILD.md](../../cortex/BUILD.md) §6:

| Consumer | Native chain | Coupling |
|---|---|---|
| `cortex/nodes/{thumbnail,quality,scene-detection,fingerprint,captioning}` | `video4j` → `opencv-ffm` → `libopencv_*.so.501` | **OpenCV 5.1**, staged into `/opt/opencv/lib` in the container image |
| `cortex/nodes/facedetect` | `video4j-facedetect-inspireface` → `libinspireface.so` + `Pikachu` model pack | InspireFace links **OpenCV 4.10** — a known ABI split against video4j's 5.1 |
| `loom/services/lucene` | `video4j-fingerprint-indexer` (`HighDimensionKnnVectorsFormat`, `MultiSectorFingerprint`) | JVM-only classes; no OpenCV native load on the Loom side |
| `loom/services/rest` | declares `video4j-fingerprint` in its pom | ⚠️ pom-level only — no `io.metaloom.video4j.*` import exists under `loom/services/rest/src`; grep shows the only `video4j` imports in all of `loom/` are the two in `LuceneSimilarityIndex` |

`video4j.version` is pinned once, in [`bom/pom.xml`](../../../bom/pom.xml) (`2.0.0-SNAPSHOT`).
Do not pin it per-module.

---

## 6. Test Setup

**There are no tests, and none are runnable.** `loom/services/video/src/test` does not
exist; `find` over the module returns `pom.xml`, `README.md`, Eclipse metadata, the single
`VideoAsset.java`, and `target/` build output.

Consequences for an agent:

- `mvn -pl loom/services/video test` is a no-op — do not use it to validate a video change.
- The module needs **no** test database. `./setup-pool.sh` (see
  [`.claude/CLAUDE.md`](../../../.claude/CLAUDE.md) and
  [../../loom/PERSISTENCE.md](../loom/PERSISTENCE.md)) is irrelevant here.
- If you ever add real code to this module, the definition of done in
  [../../guidelines/CODING.md](../guidelines/CODING.md) applies: endpoint + permission
  tests, DAO and delete-cascade tests, website docs, demo data, and a spec update — plus
  replacing this file's `🔴 EMPTY STUB` banner.

Tests that *do* exercise video today, for reference:

| Test | Location |
|---|---|
| Per-node Cortex E2E (video assets, real native calls) | `integration-test/src/test/java/io/metaloom/loom/test/integration/node/{Thumbnail,Quality,SceneDetection,Fingerprint,Captioning}NodeIntegrationTest.java` |
| Node unit tests that stub out native calls | `cortex/nodes/*/core/src/test/java/...` (e.g. `FingerprintNodePipelineTest` stubs `compute()` to avoid `Video4j.init()`) |

---

## 7. Conventions and Gotchas

- **Do not "extend" this module by reflex.** If a task says "add video support to Loom",
  the right move is almost always a Cortex node ([NODES.md](../features/nodes/NODES.md)) or a REST
  endpoint ([../../loom/RESTAPI.md](../loom/RESTAPI.md)) — *not* filling in `VideoAsset`.
  Adding a first real dependent to a nine-month-dormant stub is an architecture decision;
  ask before doing it.
- **`VideoAsset` is not the video domain model.** Video assets are rows in the `asset*`
  tables, described by [../../loom/DOMAIN.md](../loom/DOMAIN.md). A Java interface here
  would be a parallel, unsynchronised model.
- **Never add `video4j` to this module casually.** Pulling OpenCV into a Loom service module
  imports the OpenCV 5.1 native runtime requirement into the Loom container images, which
  currently do not stage `libopencv_*.so.501` (only the Cortex image does — see
  [../../cortex/BUILD.md](../../cortex/BUILD.md) §6). Loom would start failing with
  `UnsatisfiedLinkError: libopencv_video.so.501`.
- **The Dagger dependency is inherited, not used.** `com.google.dagger:dagger` comes from
  the services parent pom for all sibling modules; its presence in this module's effective
  POM does **not** mean a `@Module`/`@Component` exists here. There is none.
- **`loom/services/rest` declaring `video4j-fingerprint` with no matching import** is a
  live cleanup candidate, not something to imitate. Verify before removing — it may be
  serving a transitive/runtime purpose that grep cannot see.
- **The sibling `image` stub is in exactly the same state.** Any decision made here
  (delete, or build out) should be applied to both.

---

## 8. Where do I find …?

| Concept | Path |
|---|---|
| The entire module source | `loom/services/video/src/main/java/io/metaloom/loom/video/VideoAsset.java` |
| Its POM (parent, coordinates) | `loom/services/video/pom.xml` |
| Module registration | `loom/services/pom.xml` → `<module>video</module>` |
| dependencyManagement entry | `loom/pom.xml` (search `loom-service-video`) |
| Sibling image stub | `loom/services/image/src/main/java/io/metaloom/loom/image/` |
| Real video processing nodes | `cortex/nodes/{thumbnail,quality,scene-detection,fingerprint,captioning,facedetect,watermark}/core/` |
| video4j version pin | `bom/pom.xml` → `<video4j.version>` |
| OpenCV native staging & ABI rules | [../../cortex/BUILD.md](../../cortex/BUILD.md) §6 |
| Node → persistence mapping | [NODES.md](../features/nodes/NODES.md) |
| Video binary storage/serving | [../rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md) |
| Fingerprint similarity index | [SEARCH_LUCENE.md](../loom/SEARCH_LUCENE.md) |
| Spec tree entry point | [../../CONTEXT.md](../../CONTEXT.md) |

---

## 9. Progress Assessment

Status of the module itself:

- [x] Maven module exists and is registered in `loom/services/pom.xml`
- [x] Declared in `loom/pom.xml` dependencyManagement
- [x] Compiles (trivially — one empty interface)
- [x] Spec coverage exists (this file)
- [ ] Has any behaviour — `VideoAsset` declares **no methods**
- [ ] Has any implementation class
- [ ] Has a Dagger `@Module` / bindings
- [ ] Has an options class or any configuration
- [ ] Has any consumer anywhere in the monorepo
- [ ] Has any test
- [ ] Has meaningful `README.md` content (heading only)
- [ ] `VideoAsset` relates to `io.metaloom.loom.image.MediaAsset` (the `//String altText`
      comment implies it was intended to; it does not)

Open decisions (neither has been made — do not pick one unilaterally):

- [ ] **Decide the module's fate.** Either (a) delete `loom/services/video` and
      `loom/services/image` plus their `<module>` and dependencyManagement entries, or
      (b) give them a real purpose. Nine months of zero commits and zero consumers argue
      for (a); the maintainer has not said.
- [ ] If kept: define what a Loom-side video abstraction is *for*, given that all video
      compute is deliberately Cortex-side and all video state is DB-side.
- [ ] Audit the unused `video4j-fingerprint` dependency in `loom/services/rest/pom.xml`.
- [ ] Register this file in [../../CONTEXT.md](../../CONTEXT.md)'s spec tree listing.

---

_Last updated: 2026-08-02 — git HEAD `d930e222`_
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_