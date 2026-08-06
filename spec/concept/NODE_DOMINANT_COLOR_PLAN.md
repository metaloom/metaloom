# Dominant Colour Node — Status & Open Work

> **Status: shipped.** Kind `dominant-color`, module `cortex/nodes/dominant-color`, ~93 unit tests
> plus one integration test, descriptor + website docs + demo data all in the tree.
> This file is a **status page**: §1 says where everything lives, §2–§6 carry only what is still
> open or still non-obvious. It deliberately does not restate node lifecycle
> ([NODES.md](../features/nodes/NODES.md)), the port/content-type model
> ([../pipeline/NODE_DATA_TYPES.md](../features/pipeline/NODE_DATA_TYPES.md)), the engine
> ([../pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md)) or the new-node checklist
> ([../../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md)).
>
> **The code is the source of truth.** Where this file and the code disagree, fix this file in the
> same change ([../../guidelines/CODING.md](../guidelines/CODING.md)).

---

## 1. Already implemented

| Item | Where it lives in code |
|---|---|
| Maven module | `cortex/nodes/dominant-color/` (parent + `core`); listed in `cortex/nodes/pom.xml`, `cortex/processor/pom.xml`, `integration-test/pom.xml` |
| Node | `.../color/DominantColorNode.java` — `isProcessable = isImage()`, region loop, payload build, cache, persistence |
| Kind registration | `DominantColorNodeModule` — `@Binds @IntoSet` + `@Binds @IntoMap @StringKey("dominant-color")`; included from `cortex/cli/.../dagger/NodeCollectionModule.java`, asserted by `NodeRegistrarTest` |
| Typed ports | `IN_MEDIA` (`media/image`, ONE), `IN_DETECTIONS` (`detection/*`, **MANY**), `OUT_RESULT` (`struct/color`), `OUT_HEX` / `OUT_TERM` / `OUT_NAME_EN` / `OUT_NAME_DE` (`scalar/string`), `OUT_REGION_COUNT` (`scalar/integer`) — all in `DominantColorNode` |
| Colour science | `ColorSpaces` (sRGB↔linear↔XYZ D65↔CIELAB, LCh, HSL, hex), `ColorDistance` (CIEDE2000 for naming, ΔE76² for clustering) |
| Bilingual naming | `ColorTerms` (11 Berlin & Kay terms EN/DE + the multi-prototype Lab codebook), `ColorNamer` (achromatic gate, nearest prototype, 17-cell modifier table), `Lightness` / `Chroma` / `ColorName` |
| Clustering | `LabKMeans` — deterministic k-means++, fixed seed, deterministic empty-cluster re-seed, share ranking |
| Sampling | `PixelSampler` — ImageIO decode, stride sampling, alpha gate, distinct-colour count |
| Regions | `RegionResolver` / `RegionSource` / `RegionKind` / `Box` — whole frame + configured region + upstream detections, merged in that order |
| Persistence | `asset_json_comp` (`schemaType="dominant-color"`, `variant=""`) + `asset_node_result` ledger, `producerVersion = DominantColorNode.ALGORITHM_VERSION` (`"dominant-color/1"`) |
| Content type | `ContentTypeRegistry.STRUCT_COLOR` = `struct/color` (`loom-shared/node-model`) |
| Descriptor | `DominantColorDescriptorProvider` (22 parameters, icon `palette`, `ANALYSIS`, `defaultConcurrency=4`), ServiceLoader-registered; serialized into `website/static/pipeline-editor/node-descriptors.json` |
| Port conformance | Covered by `NodePortConformanceTest` (`dominant-color` is **not** in its `DYNAMIC_KINDS` exemption, so inputs *and* outputs are checked against the descriptor) |
| Tests | `DominantColorNodeTest` (20), `RegionResolverTest` (17), `DominantColorOptionsValidationTest` (10), `ColorSpacesTest` (9), `LabKMeansTest` (9), `ColorNamerTest` (8), `DominantColorNodePersistenceTest` (8), `DominantColorNodePipelineTest` (7), `ColorDistanceTest` (5) + `DominantColorFixtures` / assertj helpers |
| Integration test | `integration-test/.../node/DominantColorNodeIntegrationTest.java` — real clustering + real `LoomHttpClient`; asserts the German name survives JSONB and REST byte-for-byte |
| Docs & demo | `website/content/english/docs/nodes/dominant-color/index.adoc`; `DemoDatabaseInitializer.createDominantColorComp` on the first demo image |

Emitted payload per region: HEX, RGB, HSL, CIELAB + LCh, EN name, DE name, machine term, share; `dominant`
duplicates `palette[0]` on purpose.

---

## 2. Options and environment

Options live on `DominantColorNodeOptions` (`KEY = "dominant-color"`), set per pipeline-node instance.

| Option | Default | Meaning |
|---|---|---|
| `clusterCount` | `5` | k (capped by the distinct-colour count before seeding) |
| `maxSamples` | `40000` | Sample budget; drives the stride |
| `maxIterations` | `30` | Lloyd iteration cap |
| `convergenceEpsilon` | `0.5` | Centroid-movement stop threshold |
| `seed` | `42` | k-means++ seed — part of the reproducibility contract (§4) |
| `alphaThreshold` | `128` | Pixels below this alpha are skipped, never flattened |
| `minRegionPixels` | `64` | Drop threshold, applied both geometrically and post-alpha |
| `maxRegions` | `32` | Cap on *detection* regions (whole-image/configured are exempt) |
| `includeWholeImage` | `true` | Measure the whole frame |
| `useDetections` | `true` | Measure every upstream `IN_DETECTIONS` box |
| `regionX` / `regionY` / `regionW` / `regionH` | `0` | Fixed extra region; `0` size = off |
| `regionCoordinates` | `NORMALIZED` | Interpretation of the four above |
| `achromaticChroma` | `12.0` | Below this C*, hue is ignored entirely (§5) |
| `blackLightness` / `whiteLightness` | `20.0` / `85.0` | Achromatic L* bands |
| `emitPalette` | `true` | Emit the ranked palette, not just `dominant` |
| `enabled` / `processIncomplete` / `retryFailed` | inherited | `AbstractNodeOptions` |

No dedicated environment variables. Relevant existing ones:

| Variable | Default | Relevance |
|---|---|---|
| `CORTEX_NODE_WHITELIST` | all registered kinds | Omit `dominant-color` to keep it off a worker |
| `CORTEX_NODE_BLACKLIST` | — | `dominant-color` disables the kind outright (blacklist beats whitelist) |
| `CORTEX_CONF_FILENAME` | `cortex.yml` | A `nodes.dominant-color` block supplies worker-level defaults |

---

## 3. Progress Assessment

### Done

- [x] Module, node, options, Dagger `@StringKey("dominant-color")` wiring, `NodeCollectionModule`
- [x] Colour science — CIEDE2000 pinned to the full Sharma/Wu/Dalal reference dataset; exact rational sRGB transfer constants
- [x] Bilingual naming — 11 terms, multi-prototype codebook, achromatic gate, 17-cell modifier table EN/DE
- [x] Deterministic k-means++ (fixed seed, deterministic empty-cluster re-seed, stable ranking)
- [x] Three region sources merged, full coordinate-handling matrix, explicit drop/truncate accounting
- [x] Typed ports incl. the MANY `IN_DETECTIONS` input; passes `NodePortConformanceTest`
- [x] `asset_json_comp` + ledger with a hand-bumped `ALGORITHM_VERSION` asserted by a test
- [x] Descriptor, `struct/color` content type, UI icon
- [x] ~93 unit tests + 1 integration test; website docs; demo data

### Open

- [ ] **`asset_image_comp.image_dominant_color` is still never written by the pipeline.** The column
      and its `dominantColor` GraphQL field have existed since `V2.8`; today only the REST ingest
      paths (`AssetComponentEndpointService`, `AssetEndpointService`) set it from user-supplied
      `ImageInfo`. Back-filling the top hex from this node's payload would make colour *queryable*
      rather than only readable. ⚠️ `ReplaceValidatorTest` currently asserts `dominantColor` is **not**
      a replaceable field — check that before wiring a write path.
- [ ] **No colour filter node.** `dominant_color_term` was emitted as a stable facet key precisely so
      a `filter-color` node could key on it. No such node or module exists.
- [ ] **No metrics.** The node records nothing through `CortexMetrics` — in particular its
      `LocalResultCache` hit is not counted, unlike the sidecar-backed nodes'
      `metrics.recordAiCacheHit(...)`. Cheap to add; decide whether a non-AI node should report on
      the AI counters or get its own.
- [ ] **Video is out of scope.** `isProcessable` gates on `isImage()`. Per-frame colour needs a
      frame-extraction path and a `variant` scheme to hold per-frame rows.
- [ ] **No 12th `turquoise`/`Türkis` term.** `#00FFFF` and `#008080` name as *blue* — correct within
      Berlin & Kay 11, wrong to a human. Adding it is one row in `ColorTerms` plus moving three
      prototypes onto it; the architecture already supports it. `ColorTerms`' own javadoc records why
      *teal*/*mauve*/*petrol* were rejected (no one-word equivalent in both languages).
- [ ] **Thresholds are unvalidated.** The modifier bands are hand-tuned and have never been checked
      against human judgements. A small labelled set would turn "37 of 39 CSS colours correct" into a
      real regression metric.
- [ ] **`SceneLayoutNode` still has the two bugs this node avoided** (confirmed still present): its
      `LocalResultCache` is keyed on `media.absolutePath()` alone, and it scales `NORMALIZED` boxes by
      the *payload's* dimensions rather than the decoded image's. Out of scope here; tracked in
      [../pipeline/NODE_DATA_TYPES.md](../features/pipeline/NODE_DATA_TYPES.md) §10 and
      [NODE_SCENE_LAYOUT_PLAN.md](NODE_SCENE_LAYOUT_PLAN.md).

---

## 4. Why the result is reproducible (do not break this)

The same image and options must always yield the same palette — otherwise every downstream facet and
every cached result is unstable. **All three** mechanisms are load-bearing:

| Mechanism | Why it is not sufficient alone |
|---|---|
| `new Random(seed)`, seed default 42 | `java.util.Random` is bit-for-bit specified by the JLS, but k-means++ *walks the point array* — a reordered array selects different seeds |
| Stride sampling in fixed raster order | Fixes the point order that the seeder walks |
| Deterministic empty-cluster re-seeding (furthest point, ties → lowest index) | A random re-seed reintroduces nondeterminism mid-run; dropping the cluster would silently change `k` |

Ranking is by share descending, tie-broken by `L*` then `a*` then `b*` — never left to map iteration
order. `k` is capped by the distinct-colour count *before* seeding.

---

## 5. Conventions and Gotchas

| Rule | Why |
|---|---|
| **Two distance functions, and the split is a correctness requirement** | Lloyd's algorithm terminates only because each step lowers the within-cluster sum of squares, which needs the arithmetic mean to be the distance's centroid — true for ΔE76², false for CIEDE2000 (violates the triangle inequality, not a Bregman divergence). CIEDE2000 runs once per emitted colour per prototype, inside `ColorNamer` |
| **Do not weaken `ColorDistanceTest`** | Roughly half of published CIEDE2000 implementations get hue-averaging or the `Rt` rotation branch wrong, invisibly on ordinary colours. All 34 Sharma/Wu/Dalal reference pairs are pinned to `1e-4` |
| **A grey image is the most common degenerate input** | `cBar == 0` and `cBarP == 0` both divide zero by zero → `NaN`. Both guards are explicit in `ColorDistance.ciede2000`, pinned by `testGreyAgainstGreyIsZeroNotNaN` |
| **Several Lab prototypes per term, never one anchor** | With one anchor, lightness is counted twice and `#000080` navy / `#191970` midnightblue name as *purple*, `#00FF00` as *yellow* by 0.1 ΔE. Pinned by `ColorNamerTest.testTheColorsThatMotivatedTheMultiPrototypeCodebook` — thin the codebook and that test fails first |
| **Prototypes are stored as hex strings, converted in a static initialiser** | Hex is auditable by eye and the conversion is already tested; hand-typed Lab triples carry transcription risk |
| **Achromatic colours bypass hue entirely** | Below `achromaticChroma` the namer decides on lightness alone — no prototype lookup, no chroma modifier — which makes `greyish grey` impossible by construction. 12.0 rather than 10.0 because `#708090` slategray has `C* = 10.79` and is plainly grey |
| **`Lab.hue()` returns `null`, not 0, below `HUE_EPSILON`** | `atan2(0,0)` is 0, and a caller taking that at face value reports pure grey as *red*. `HUE_EPSILON` is `1e-4` rather than 0 because the D65 white point is not exactly consistent with the sRGB primary matrix (residual chroma ~`2e-5`) |
| **Stride sampling, never bilinear downscaling** | Interpolation invents colours that are not in the image (a red/blue striped flag downscales to purple) and bleeds the RGB of fully transparent pixels into their neighbours before the alpha gate can discard them. A deliberate divergence from `DepthImages.downscale` |
| **Transparent pixels are skipped, never flattened** | `DepthImages.toOpaque` flattens onto white, correct for a depth map; here it would make every logo on a transparent background come back *white*. `testFullyTransparentPixelsAreSkippedRatherThanFlattened` writes blue *behind* zero alpha so any blend is detectable |
| **A failure path ends in `abort()`, never `failure(...).next()`** | `NodeContextImpl.next()` ignores `failureCause` and returns SUCCESS. Eleven other nodes still have that bug ([NODES.md](../features/nodes/NODES.md) §10) |
| **The cache key covers upstream payloads and options, not just the path** | `cacheKey(ctx)` hashes the path + every wired detection payload + all options. Keying on the path alone (as `SceneLayoutNode` does) returns the first detector's answer when the file is re-run behind a different detector — a stale result that never surfaces as an error. `testChangedUpstreamDetectionsMissTheCache` pins it |
| **An undecodable image FAILS; an empty result SKIPS** | A fully transparent PNG or a detector that found nothing is a normal outcome; failing it would block downstream nodes and pollute the run summary |
| **Fixtures are PNG, and their filenames end `.png`** | JPEG chroma subsampling puts colours into the decoded image that were never written; and `FilterHelper.isImage` decides the media type from the extension alone, so a wrongly-named fixture makes every test pass vacuously |
| **The German nouns are neuter and capitalised** | `Rot Orange Gelb Grün Blau Violett Rosa Braun Schwarz Grau Weiß` — that is what makes the strong-declension `-es` adjective correct for all eleven with no article and no gender table |
| **The term table is Java, not a resource** | ~60 strings keyed on the `Lightness`/`Chroma` enums, compile-checked, and immune to an encoding slip turning `Weiß` into a replacement character on a non-UTF-8 platform |
| **Bump `ALGORITHM_VERSION` whenever `ColorTerms` or `ColorNamer` changes** | The codebook and modifier table decide every emitted name. `DominantColorNodePersistenceTest.testTheAlgorithmVersionIsRecordedAsTheProducerVersion` asserts the exact string, so a silent retune fails the build |
| **CMYK JPEGs fail** | `ImageIO.read` returns `null` for them — same as `FacedetectNode` and `QualityNode` |

### Upstream detection contract

`IN_DETECTIONS` accepts exactly what `FacedetectNode` emits:
`{imageWidth, imageHeight, coordinates, detections:[{index, type, label, frame, bbox{x,y,w,h}, confidence}]}`.
Emission order is part of the contract: whole → configured region → detections (source order, then
payload `index`). Ids are `label + "-" + index`, node-id-prefixed when more than one source is wired.

| Case | Handling |
|---|---|
| `NORMALIZED` | Scale by the **decoded image's** dimensions, never the payload's |
| `ABSOLUTE_PIXELS`, dims match | Use as-is |
| `ABSOLUTE_PIXELS`, dims differ | Rescale by `(imgW/payloadW, imgH/payloadH)` and `log.info` naming both sizes |
| `ABSOLUTE_PIXELS`, dims absent | The facedetect *video* path emits this; assume the decoded image's pixel space |
| `coordinates` absent/unknown | Treat as `ABSOLUTE_PIXELS` and `log.warn` |
| `frame != 0` | Dropped and counted — in an image pipeline this can only be a mis-wired graph |
| Malformed JSON | Warned and ignored, never thrown |

---

## 6. Key Classes Reference

| Class | Package / module | Purpose |
|---|---|---|
| `DominantColorNode` | `io.metaloom.cortex.node.color` (cortex/nodes/dominant-color) | The node: ports, region loop, payload, persistence, cache key |
| `DominantColorNodeOptions` | same | Options + `validate()`; `KEY = "dominant-color"` |
| `DominantColorNodeModule` | same | Dagger `@Binds @IntoSet` + `@IntoMap @StringKey("dominant-color")` |
| `ColorSpaces` | same | sRGB ↔ linear ↔ XYZ(D65) ↔ CIELAB, LCh, HSL, hex |
| `ColorDistance` | same | CIEDE2000 (naming) + squared Euclidean (clustering), with both zero-chroma guards |
| `ColorTerms` | same | 11 bilingual terms + the chromatic Lab codebook |
| `ColorNamer` | same | Achromatic gate, nearest prototype, modifier composition |
| `Lightness` / `Chroma` / `ColorName` | same | The two modifier bands and the named result |
| `LabKMeans` | same | Deterministic k-means++ / Lloyd over a flat Lab array |
| `PixelSampler` | same | ImageIO decode, stride sampling, alpha gate, distinct-colour count |
| `RegionResolver` / `RegionSource` / `RegionKind` / `Box` | same | Configuration + upstream boxes → ordered regions |
| `ColorResult` · `Rgb` / `Lab` / `Hsl` | same | One region's ranked palette; colour value records |
| `DominantColorDescriptorProvider` | `io.metaloom.loom.nodes.spec` (loom-shared/node-model) | Palette entry, ports, 22 form parameters |
| `ContentTypeRegistry.STRUCT_COLOR` | same | `struct/color` |
| `AbstractMediaNode` | `io.metaloom.cortex.common.node` | Lifecycle, `recordNodeResult`, `resultRef` |

---

## 7. Test setup

No database is needed for anything but the integration test.

```bash
./mvnw -pl cortex/nodes/dominant-color/core test      # ~93 tests, pure JVM
./mvnw -pl loom-shared/node-model test                # descriptor + SPI discovery counts
./mvnw -pl cortex/cli test -Dtest=NodeRegistrarTest   # kind must be advertised
./setup-pool.sh                                       # required before the IT
./mvnw -pl integration-test test -Dtest=DominantColorNodeIntegrationTest
```

| Test | Guards against |
|---|---|
| `ColorDistanceTest` | A wrong CIEDE2000 branch, invisible on ordinary colours; the grey `NaN` |
| `ColorSpacesTest` | Rounded EPS/KAPPA constants — round-trips all 216 web-safe colours + 40 dark greys exactly |
| `ColorNamerTest` | A thinned prototype codebook; `greyish grey`; a German string drifting from its English counterpart |
| `LabKMeansTest` | Nondeterminism, division by zero, unstable ranking |
| `RegionResolverTest` | The coordinate-handling matrix in §5 |
| `DominantColorNodeTest` | Alpha gate, cache key, fail-vs-skip, exact colours end to end |
| `DominantColorNodePersistenceTest` | Component-then-ledger ordering, the algorithm version, silence on skip |
| `DominantColorNodeIntegrationTest` | The German name reading back byte-for-byte through JSONB and REST — the only place UTF-8 survival across the whole persistence chain is asserted |

---

## 8. Where do I find …?

| Need | Path |
|---|---|
| The node | `cortex/nodes/dominant-color/core/src/main/java/io/metaloom/cortex/node/color/DominantColorNode.java` |
| Typed port constants | same file, top of the class |
| The colour-space maths | `.../color/ColorSpaces.java` |
| CIEDE2000 + its two zero-chroma guards | `.../color/ColorDistance.java` |
| The bilingual term table and Lab codebook | `.../color/ColorTerms.java` |
| The achromatic gate and modifier table | `.../color/ColorNamer.java` |
| k-means, seeding, empty-cluster re-seed | `.../color/LabKMeans.java` |
| Stride sampling and the alpha gate | `.../color/PixelSampler.java` |
| The coordinate-handling matrix | `.../color/RegionResolver.java` |
| The Sharma reference dataset | `.../src/test/java/io/metaloom/cortex/node/color/ColorDistanceTest.java` |
| Synthetic PNG fixtures | `.../src/test/java/io/metaloom/cortex/node/color/DominantColorFixtures.java` |
| The descriptor and its 22 parameters | `loom-shared/node-model/src/main/java/io/metaloom/loom/nodes/spec/DominantColorDescriptorProvider.java` |
| The `struct/color` content type | `loom-shared/node-model/.../spec/ContentTypeRegistry.java` |
| Kind registration | `cortex/cli/src/main/java/io/metaloom/cortex/cli/dagger/NodeCollectionModule.java` |
| Port-vs-descriptor conformance | `integration-test/.../node/NodePortConformanceTest.java` |
| The UI icon mapping | `loom-ui/src/features/pipeline/PipelineEditor.tsx` (`ICON_MAP`, key `palette`) |
| Demo data | `loom/core/.../boot/DemoDatabaseInitializer.java` (`createDominantColorComp`) |
| Customer-facing docs | `website/content/english/docs/nodes/dominant-color/index.adoc` |

---
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_