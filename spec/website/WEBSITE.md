# MetaLoom Customer-Facing Website (Hugo)

This document specifies the MetaLoom customer-facing website — a [Hugo](https://gohugo.io/)
static site that renders the marketing landing page, blog and the **customer-facing product
documentation** for Loom and Cortex. It is written for an AI coding agent that needs to add,
edit or restructure site content or fix the build/publish flow.

> Scope boundary: this spec covers the **website content and build** only. The product itself
> (Loom server, Cortex engine, REST API, pipeline model) is specified in the sibling spec files
> under `spec/`. The `.adoc` documentation pages describe those systems for end users but are
> not the source of truth for their behavior — cross-check code and the product specs when the
> two disagree.

## TL;DR

* Source project: `metaloom/website/` — Hugo site, theme `meghna-hugo` (vendored/customized).
* Customer-facing docs live in `website/content/english/docs/**` as **AsciiDoc** (`.adoc`).
* Content language is AsciiDoc (needs `asciidoctor` on `PATH`); the landing page is data-driven
  from `website/data/en/*.yml` + theme partials.
* Build with `website/build.sh` → output goes to `website/dist/` (`publishDir = "dist"`).
* Publish: the separate **`metaloom-website`** repo (a sibling checkout, *not* part of this
  repo) runs `pull.sh` to copy `website/dist` → its `docs/` folder and serves it via **GitHub
  Pages** at `metaloom.io` (see [Publishing](#publishing-flow)).

## Architecture / Component Relationships

```
                 authors edit .adoc / .yml
                          │
                          ▼
   ┌───────────────────────────────────────────────┐
   │  metaloom/website/  (Hugo source, this repo)   │
   │                                                │
   │  content/english/docs/**   ← customer docs     │
   │  content/english/blog/**   ← blog posts        │
   │  data/en/*.yml             ← landing-page data │
   │  themes/meghna-hugo/       ← layouts + CSS     │
   │  config.toml               ← site config       │
   └───────────────────────────────────────────────┘
            │ build.sh: yarn build (LESS→CSS) + hugo
            ▼
   ┌───────────────────────────────────────────────┐
   │  website/dist/   (generated, git-ignored)      │
   └───────────────────────────────────────────────┘
            │ metaloom-website/pull.sh copies dist → docs/
            ▼
   ┌───────────────────────────────────────────────┐
   │  metaloom-website/  (separate repo, staging)   │
   │  docs/  + CNAME(metaloom.io) → GitHub Pages     │
   └───────────────────────────────────────────────┘
            │ git push
            ▼
        https://metaloom.io
```

Two distinct projects, do not confuse them:

| Project | Path | Role |
| --- | --- | --- |
| `metaloom-website` (Hugo source) | `metaloom/website/` (this repo, Maven artifactId `metaloom-website`) | Editable source: content, theme, config. Build produces `dist/`. |
| `metaloom-website` (staging/publish) | `/home/defaultuser/workspaces/metaloom/metaloom-website/` (sibling repo) | Holds the *built* site under `docs/` and publishes via GitHub Pages. Only `pull.sh` + generated `docs/`. Do not hand-edit `docs/`; it is overwritten on every pull. |

## Building

The Hugo source is `website/`. All commands below assume you `cd website` first.

### Prerequisites

* **Hugo** (extended, **≥ 0.158**) — the config uses the post-0.158 multilingual keys
  (`[languages.en] locale`/`label`, not the old `languageCode`/`languageName`) and templates use
  `hugo.Data` / `site.Language.Locale`. Older Hugo (≤0.131) will error on these.
* **Node + npm** — used by the theme to compile CSS (`themes/meghna-hugo` has its own
  `package.json`). `build.sh` prefers `yarn` if present and falls back to `npm` otherwise.
* **asciidoctor** — required because docs/blog are AsciiDoc. `config.toml` explicitly allows the
  `asciidoctor` external binary under `[security.exec] allow`. Without it, `.adoc` pages render
  empty or Hugo errors.

### Commands

| Command | What it does |
| --- | --- |
| `./build.sh` | Full build: `cd themes/meghna-hugo && (yarn\|npm) install && … build` (compiles theme CSS), then `hugo` at the project root → writes `dist/`. |
| `./watch.sh` | Runs `build.sh` then `hugo server -b http://localhost:1313/` for live local preview. |
| `hugo` | Site build only (assumes theme CSS already built). Output → `dist/`. |

`build.sh` uses `set -o errexit -o nounset` — a missing `asciidoctor`/`hugo` (or both `yarn` **and**
`npm`) fails the whole script. `dist/` and `docs/` are git-ignored in this repo (see `website/.gitignore`), so a
build never dirties the working tree with output.

### Maven integration

`website/pom.xml` is a `packaging=pom` module of the `metaloom-parent` reactor (artifactId
`metaloom-website`). It does **not** invoke Hugo — it exists so the website participates in the
Maven module graph/versioning. The real build is `build.sh`.

## Folder Structure

```
website/
├── config.toml            # Hugo site config: baseURL, theme, menu, plugins, params
├── build.sh               # theme CSS build + hugo
├── watch.sh               # build + hugo server (local preview)
├── pom.xml                # Maven pom module (no build logic)
├── .gitignore             # ignores dist/, docs/, resources/, node_modules, target ...
├── content/
│   └── english/           # contentDir for the "en" language (config.toml)
│       ├── docs/          # ★ CUSTOMER-FACING DOCUMENTATION (AsciiDoc)
│       ├── blog/          # blog posts (one folder per post, index.adoc)
│       └── author/        # blog author pages
├── content-off/           # DISABLED content (not built; parked pages). e.g. an old POC post
├── data/en/*.yml          # landing-page section data (banner, feature, team, funfacts, ...)
├── i18n/en.yaml           # UI string translations (menu labels, "Read more", etc.)
├── static/                # copied verbatim to dist/: images/, CNAME, .nojekyll, robots
│   └── docs/examples/openapi.{json,yaml}  # staged OpenAPI doc: downloadable + rendered by Swagger UI
├── resources/_gen/        # Hugo asset cache (git-ignored)
├── themes/meghna-hugo/    # vendored + customized theme (layouts, LESS, JS plugins)
└── dist/                  # BUILD OUTPUT (git-ignored) → what gets published
```

### Documentation tree (`content/english/docs/`)

The docs are the primary customer-facing deliverable. Structure mirrors the two product
subsystems plus shared conceptual pages:

```
docs/
├── _index.adoc            # Docs landing page: card grid, "Start Here", concepts, path table
├── variables.adoc-include # shared AsciiDoc attributes (:icons: font, :toc:, highlighter)
├── getting-started/       # run the demo container locally (weight: 1 → sorts first)
├── deployment/            # Container Images (loom-server/-demo, cortex-server, session-runner) + ports
├── playbooks/             # ── Task-oriented end-to-end guides (weight: 3) ──
│   ├── _index.adoc        # card grid + § "Which node kinds a worker can actually run"
│   ├── docker/            # single-host stack: postgres + loom-server + cortex, volumes, compose
│   ├── kubernetes/        # in-cluster stack, SA + sandbox RBAC/quota/NetworkPolicy, Helm packaging
│   ├── transcription/     # whisper pipeline → transcripts the chat agent can search
│   ├── scene-analysis/    # scene-detection + whisper/thumbnail; audio vs visual routes
│   ├── translation/       # extract (whisper/tika/ocr/vlm) → translate → optional tts dubbing
│   └── python-node/       # custom node as a Python worker (wire protocol + node descriptor)
│                          #   incl. § "Generating a Node With a Coding Agent" (paste-ready prompt)
├── operation/             # Loom & Cortex runtime model — architecture, worker lifecycle (Loom owns the DAG)
├── pipeline/              # pipeline mechanism — Loom runs the graph, delegates tasks to Cortex
├── nodes/                 # ── Nodes subsystem (top-level "box" like Loom/Cortex) ──
│   ├── _index.adoc        # node catalogue + requirements-at-a-glance
│   └── <kind>/            # one page per node: hash, fingerprint, consistency, thumbnail, facedetect,
│                          #   facedescription, ocr, tika, whisper, llm, quality, scene-detection,
│                          #   captioning, dedup, filesystem-source, loom, filters
│                          #   (each: description, applies-to, inputs, output keys, requirements, config, use cases)
├── loom/                  # ── Loom subsystem ──
│   ├── _index.adoc        # component overview + links
│   ├── rest-api/          # REST API reference (embeds Swagger UI)
│   ├── java-client/       # typed Java HTTP client usage
│   ├── authentication/    # JWT / OAuth2 auth model
│   ├── configuration/     # YAML config + env vars (incl. LOOM_AI_*, LOOM_AGENT_SANDBOX_*, LOOM_AGENT_MEMORY_*)
│   ├── metrics/           # Prometheus scrape endpoint (:8989) + the loom_* meter catalog
│   ├── features/          # assets, users, groups, roles, tags, pipelines
│   ├── chat/              # Chat & AI Agent — agentic loop, Sessions, Skills (w/ examples), Memory, coding sandbox (+ deployment)
│   ├── artifacts/ · maven-artifacts/ · containers/ · helm-chart/  # deploy/coordinates
│   │                          #   rest-api/ = spec download (yaml/json) + Swagger UI explorer + tables
│   └── examples/          # snippets from the /examples module
├── legal/                 # ── Legal & Licensing (weight: 9 → sorts last) ──
│   ├── _index.adoc        # hub: platform license (Apache-2.0), commercial-use warning, card grid
│   ├── model-licenses/    # per-node model/runtime license inventory; non-commercial call-outs
│   └── ai-disclosure/     # how the source was produced (2023–2025 hand-written, 2026+ AI-assisted)
├── cortex/                # ── Cortex subsystem (now a daemon that serves nodes) ──
│   ├── _index.adoc        # engine overview
│   ├── configuration/ · monitoring/ · metrics/ · artifacts/   # node pages live under top-level nodes/ now;
│   │                                                          #   metrics/ = cortex_* catalog on :8093
│   ├── maven-artifacts/ · containers/ · examples/   # examples cover Java node, Java daemon, Python worker
└── (legacy stubs)         # rest/, test/, configuration/ — old placeholder pages, see Gotchas
```

> The **coding sandbox** deployment (podman/k8s backends, RBAC, `LOOM_AGENT_SANDBOX_*`) now lives
> inside `loom/chat/` (§ Coding Sandbox), not a separate `loom/agent-sandbox/` page. The per-node
> reference moved from `cortex/nodes/` to the top-level `nodes/` section. The landing feature list is
> data-driven from `data/en/feature.yml` (includes the Chat & AI Agent and Cortex Processing Nodes
> items).
>
> `docs/cortex/features/` was removed — its capability copy is merged into `docs/nodes/_index.adoc`
> (§ Processing Capabilities / § Authoring Your Own Node) and its deployment copy into
> `docs/operation/` (§ Deployment Patterns). `docs/interaction/` was renamed to `docs/operation/`.
> **Cortex has no offline mode** — do not reintroduce "online vs offline" copy; `isOfflineMode()` in
> the code only means "no Loom client configured". Webhooks are likewise not a product feature and
> must not be listed in the docs or `data/en/feature.yml`.

Every content page is a **page bundle**: a directory containing `index.adoc` (a leaf page) or
`_index.adoc` (a section/branch page). Co-located assets (images) go in the same folder.

### The OpenAPI spec: download + API explorer

The REST API page (`docs/loom/rest-api/`) offers the API in three forms:

1. **Download** — `openapi.yaml` / `openapi.json` links in a card grid, served straight from
   `website/static/docs/examples/`.
2. **API Explorer** — an embedded **Swagger UI** (`plugins/swagger/*`, wired in `config.toml`)
   that renders the same document. It covers ~130 paths, grouped into ~35 resource tags.
3. **Endpoint Reference** — hand-written summary tables for the most-used routes. These are a
   reading guide; the explorer is the authoritative list.

The OpenAPI document is **generated from the Loom server's endpoint registry** — never written by
hand — and must be regenerated whenever endpoints change.

#### Regenerating

1. The generation logic lives in `io.metaloom.loom.rest.openapi.LoomOpenAPI`
   (module `loom/services/rest`). It builds a throw-away `ApiRouter`, registers **every** endpoint
   of the rest module on it, runs the external `io.metaloom.vertx.openapi.OpenAPIGenerator` over
   the result and then *polishes* the raw route dump into a usable document:
   Vert.x `:uuid` paths become OpenAPI `{uuid}` templates with declared path parameters, operations
   get tags/summaries/operationIds, JWT + cookie security schemes are declared (with the pre-auth
   routes opting out), the standard 400/401/403/404/500 error responses are filled in and the route
   examples are inlined as real JSON instead of escaped strings.
2. `io.metaloom.loom.doc.impl.OpenAPIGenerator` (module `loom/doc`) drives it and writes both
   `loom/doc/src/main/generated/openapi.json` and `openapi.yaml`. It runs from `loom/doc` because
   the chat/memory endpoints live in `loom/agent/*`, which depends on the rest module and therefore
   cannot be referenced from `LoomOpenAPI` itself — they are passed in through the extra-endpoint
   factory.
3. `io.metaloom.loom.doc.ExampleGenerator#main` runs all doc generators (OpenAPI + Loom config +
   REST model). Run it after adding/removing/renaming REST endpoints or changing DTOs:

   ```bash
   mvn -q -pl loom/doc -am -DskipTests install
   cd loom/doc && mvn -q exec:java -Dexec.mainClass=io.metaloom.loom.doc.ExampleGenerator
   ```
   (Working directory must be `loom/doc/` — the generator writes to the relative
   `src/main/generated/`.)
4. Stage **both** generated files into the site (copy them — nothing does this automatically):

   ```bash
   cp loom/doc/src/main/generated/openapi.json website/static/docs/examples/
   cp loom/doc/src/main/generated/openapi.yaml website/static/docs/examples/
   ```
5. A running server serves the same document for its own endpoint set at `/api/v1/openapi`
   (YAML), `/api/v1/openapi.yaml` and `/api/v1/openapi.json`, with the address it was fetched from
   filled in as the server URL.

`LoomOpenAPITest` guards the generation and the polish step (path templating, path parameters,
operation descriptions, security schemes, inlined examples, endpoint coverage). Run
`mvn -pl loom/services/rest test` after endpoint changes.

> ⚠️ The checked-in `loom/doc/src/main/generated/openapi.*` can go stale relative to the code.
> Regenerate them in the same change as any REST endpoint edit, and re-stage them for the website.

#### Swagger UI wiring (`themes/meghna-hugo/static/plugins/swagger/swagger.js`)

* The plugin JS is loaded on **every** page (`[[params.plugins.js]]` in `config.toml`), so the
  script must bail out when `#swagger-ui` is absent — otherwise SwaggerUIBundle renders into `null`
  and throws *React error #200* site-wide.
* The `url` must stay **site-relative** (`/docs/examples/openapi.json`). An absolute
  `http://localhost:1313/...` URL makes the published site fetch the *visitor's* machine, which
  fails CORS and triggers the browser's Local Network Access prompt
  ("metaloom.io wants to access other apps and services on this device").
* The mount point is a raw-HTML block `<div id="swagger-ui"></div>` in
  `docs/loom/rest-api/index.adoc`; a per-page `data-openapi-url` attribute overrides the default URL.
* Explorer options: `docExpansion: 'none'` (the spec is too big to open expanded), `filter: true`,
  `deepLinking: true` (so `#/users/getUsersByUuid` links to a single operation), alphabetical tag
  and operation sorting, `persistAuthorization` and `validatorUrl: null` — the published site must
  never ship a reader's spec to `validator.swagger.io`.
* **Contrast:** Swagger UI ships light-theme CSS and assumes a white page. On this dark site its
  headings and descriptions would be near-invisible, so `#swagger-ui` is given its own light surface
  in `less/includes/custom.less` (mirrored into the compiled `assets/css/main.css`) rather than
  being restyled operation by operation.
* Card headings inside raw-HTML blocks on a docs page carry `data-toc-skip` so bootstrap-toc keeps
  them out of the sidebar TOC.

### Node diagrams (`nodeviz`)

Every page under `docs/nodes/<kind>/` opens with a generated diagram of that node in a pipeline —
typed inputs on the left, the node in the middle, typed outputs on the right, an animated flow, and a
tab per alternative configuration. The page carries only a JSON spec inside a passthrough block:

```asciidoc
++++
<div class="ml-nodeviz" data-nodeviz='{"kind":"ocr","applies":"Image","badge":"Tesseract / tessdata",
  "persist":"asset_json_comp + ledger",
  "inputs":[{"t":"image","l":"image or scan"}],
  "outputs":[{"t":"text","l":"ocr_text","d":"recognised glyphs"}]}'></div>
++++
```

* The renderer is `themes/meghna-hugo/static/plugins/nodeviz/nodeviz.js` (wired in `config.toml` like
  Swagger/GraphiQL, and a no-op on pages without `.ml-nodeviz`). Geometry, icons and the animation
  live there — **change the drawing once, all 19 pages follow**.
* Port fields: `t` = data type (drives icon + colour), `l` = label, `d` = optional sub-label, `opt`
  = dashed "optional" styling. Use `configs: [{name, note, inputs, outputs}, …]` for alternative
  configurations; a single `inputs`/`outputs` pair is the shorthand for one config.
* Types: `image · video · audio · document · file · text · json · number · boolean · hash · vector ·
  face · bbox · timeframe · segments · path · flag · branch · action`. Unknown types fall back to a
  neutral dot, so add new ones to `TYPES` + `icon()` rather than inventing labels.
* The type key is rendered once on `docs/nodes/_index.adoc` via `<div class="ml-nodeviz-legend"></div>`.
* The spec lives in a **single-quoted HTML attribute** — never use an apostrophe inside the JSON.
* Styling is `.nv-*` in `less/includes/custom.less`, mirrored into the compiled `assets/css/main.css`.

### Hand-drawn figures

Non-node diagrams are inline SVG in a `++++` block using the shared `.ml-*` vocabulary in
`custom.less` (`ml-box-container`, `ml-box-part`, `ml-edge`, `ml-chip`, `ml-step`, `ml-flow`,
`ml-box-gpu`, `ml-box-dyn`, `ml-deny`): the architecture diagram and the container-level **deployment
overview** on `docs/operation/`, and the **coding sandbox lifecycle** on `docs/loom/chat/`. Give each
a `<title>` + `<desc>` referenced from `aria-labelledby` — they are the accessible description of the
figure. All six **playbook** figures follow the same house style, wrapped in
`<div class="ml-figure">` with `class="ml-arch-svg"` on the `<svg>`.

> **Prefix marker ids per page.** `<marker>` ids are document-global, so two figures reusing
> `ml-arrow` on one page collide and one figure loses its arrowheads. Each page uses its own prefix —
> `ml-dk-*` (docker), `ml-k8s-*` (kubernetes), `ml-tr-*`, `ml-sc-*`, `ml-tl-*`, `ml-py-*`.

> **Do not use ASCII art for architecture or flow diagrams.** It was used in an early draft of the
> playbooks and replaced; fenced code blocks are for commands, config and JSON only.

> **Animated figures must not change height.** The dispatch animation on `docs/operation/` swaps its
> caption text every phase; when the caption box was allowed to grow from one line to two, the page
> height oscillated, which toggled the window scrollbar and jittered the layout. The caption now has
> a fixed height, and `custom.less` sets `html { scrollbar-gutter: stable; }` so a scrollbar
> appearing never reflows the page.

### Updating the staged GraphQL schema (GraphiQL explorer)

The **GraphQL API** page (`docs/loom/graphql-api/`) embeds a **GraphiQL** explorer (the
`plugins/graphiql/*` assets, wired in `config.toml` exactly like Swagger UI). It builds the schema
in-browser from a staged SDL file, so it works with no backend — only live query *execution* needs a
running server.

* The staged schema is `website/static/docs/examples/schema.graphql`, served at the site-relative
  path `/docs/examples/schema.graphql` (the default `data-schema-url` in
  `themes/meghna-hugo/static/plugins/graphiql/graphiql.js`).
* It is a **copy** of the Loom SDL and can go stale — regenerate it in the same change as any schema
  edit:

  ```bash
  cp loom/services/graphql/src/main/resources/loom.graphqls \
     website/static/docs/examples/schema.graphql
  ```
* `graphiql.js` mirrors `swagger.js`: it is loaded globally but no-ops unless a `#graphiql` mount
  div is present, and a per-page `data-graphql-url` attribute can point the explorer at a live
  endpoint (the GraphiQL analogue of Swagger's `data-openapi-url`). A running Loom server also serves
  a live GraphiQL at `/graphiql`.

## Capturing Loom UI screenshots

The **Loom UI** docs page (`content/english/docs/ui/`) is illustrated with dark-mode screenshots of the
running application. They are produced by driving a headless Chromium against the **demo container** with
a Playwright script, and are checked in as page-bundle images (co-located with `index.adoc`, referenced
`image::name.png[Alt,role=img-fluid]`). Any agent can refresh them by repeating the steps below.

> **Always build a fresh demo image first.** The local `metaloom/loom-demo:latest` can lag behind the
> source. Rebuild it so the screenshots reflect the current UI and server — do not screenshot a stale
> image. This mirrors what `e2e.sh` does.

### 1. Build a fresh demo image

From the repo root:

```bash
mvn -T 8 clean package -DskipTests -pl loom/containers/demo -am   # → loom/containers/demo/target/loom-demo.jar
( cd loom-ui && npm run build )                                   # → loom-ui/build (bundled into the image)
( cd loom/containers && ./build-containers.sh jvm demo )          # → metaloom/loom-demo:latest
```

> Use `jvm demo` explicitly. A bare `./build-containers.sh demo` also tries to build the *native* image
> (needs GraalVM) and will fail.

### 2. Start the demo stack

The demo container is **not** self-contained — start Postgres first (it provides the `postgres-demo`
container on the shared `dev` docker network):

```bash
./start-postgres.sh
./start-demo.sh
```

* UI URL: **http://localhost:8092/ui/** (the UI is served by a Vert.x `StaticHandler` at `/ui/*`, *not* at
  the site root).
* Credentials: **admin** / **finger** (`LOOM_INITIAL_PASSWORD`).
* The database is auto-seeded by `DemoDatabaseInitializer` (assets, pipelines, faces, users, roles, tags,
  API keys, skills with two versions each, published chat sessions with context references, agent memory
  notes, per-asset tasks …), so every screen has real content.
* **Image assets carry real bytes.** The initializer paints them at runtime and stores them
  content-addressed, so the asset browser and detail view show pictures rather than placeholder icons.
  Videos, audio and PDFs have no browser-renderable preview and stay as placeholders — that is expected,
  not a broken capture.
* The demo image sets `LOOM_AGENT_MEMORY_ENABLED=true`; without it the memory endpoints are not
  registered and the Memory screen reads "No memory scopes are available".

### 3. Capture

The capture script is `loom-ui/scripts/capture-ui-screenshots.mjs`. It uses the Playwright + Chromium that
are already installed under `loom-ui/` (no extra install needed), logs in, forces dark mode
(`localStorage["loom-ui-theme"]="dark"`), and navigates the app by **clicking sidebar items** — the SPA has
no router `basename` under `/ui/`, so deep-link reloads do not work; client-side navigation does.

```bash
cd loom-ui
node scripts/capture-ui-screenshots.mjs        # writes PNGs into ../website/content/english/docs/ui/
```

Env overrides: `UI_BASE_URL` (default `http://localhost:8092/ui/`), `LOOM_USER`, `LOOM_PASS`, `OUT_DIR`.

Route/action → filename (keep stable so refreshes overwrite in place):

[cols="1,2"]
|===
| File | Source
| `chat.png` | `/` — Chat (landing)
| `chat-sessions.png` | AI → Chat Sessions
| `skills.png` | AI → Skills
| `memory.png` | AI → Memory
| `assets.png` | Content → Assets
| `asset-detail.png` | Assets → the `sunset-beach.jpg` card (targeted by name: list order is not stable, and this asset is the richest — stored binary, tags, reaction, detections, task)
| `library.png` | Content → Library
| `tags.png` | Content → Tags
| `tasks.png` | Content → Tasks
| `face-detection.png` | Content → Detection (defaults to the Faces tab)
| `pipeline-editor.png` | Management → Pipelines
| `pipeline-versions.png` | Pipelines → version badge (history popover open)
| `cortex.png` | Management → Cortex
| `monitoring.png` | Management → Monitoring (extra settle time — Recharts animates its series in)
| `users.png` | Management → ACL → Users
| `acl-roles.png` | Management → ACL → Permissions (ACL matrix)
| `api-keys.png` | Management → ACL → API Keys
|===

> **The ACL screens sit in a collapsible sub-group** that starts closed. `openAclGroup()` clicks
> `[data-testid="sidebar-group-acl"]` before those three captures; a nav click alone will time out.
> `clickNav` matches `^\d*<label>\d*$` so an entry with a badge counter (Tasks) still resolves.

> Per-library contents are unseeded in a bare demo. The script captures whatever the demo actually
> contains — do not fabricate data.

> **The library view auto-selects the first library, which holds nothing.** `library.png` therefore
> clicks the first entry that is not labelled "0 assets" before shooting, so the grid (and its
> thumbnails) is what ends up in the screenshot.

> **Asset thumbnails only render when Loom serves the UI.** The grid points an `<img>` at
> `/api/v1/assets/:uuid/binary/data`, which cannot carry an `Authorization` header and authenticates
> with the `__Host-loom_token` cookie instead. Served from the container at
> `http://localhost:8092/ui/` that works; behind a `vite` dev proxy the browser does not store the
> cookie and every preview 401s back to the type placeholder. **Screenshots that must show
> thumbnails have to come from the container** — rebuild it after a UI change:
> `( cd loom-ui && npm run build ) && ( cd loom/containers && ./build-containers.sh jvm demo )`,
> then `./start-demo.sh`. Recreating the demo container against an existing database yields 404s on
> the binaries (the rows outlive the seeded files), so re-run `./start-postgres.sh` first for a
> clean re-seed.

> **Click-to-zoom.** Docs content images (`.docs-main-content .imageblock img`) get a `zoom-in`
> cursor and open in a full-screen modal *lightbox* on click (dismiss via backdrop click, the ×
> button, or `Escape`). It is a theme feature — CSS in `themes/meghna-hugo/less/includes/custom.less`
> (`.ml-lightbox*`, mirrored in the compiled `assets/css/main.css`) and vanilla JS appended to
> `themes/meghna-hugo/assets/js/script.js` — so it applies to any docs page with images, not just the
> Loom UI page. It does not alter the page layout.

#### Populating the Cortex view

`cortex.png` needs a live worker, otherwise the list is empty. Build `cortex-server` **from the same
source revision as the demo image** (an older image fails the registration handshake — "Not registered.
Send REGISTER first.") and start it on the shared `dev` network. It needs a local OpenCV 5.1 build
(`../opencv/build/lib/libopencv_core.so.501`; set `OPENCV_LIB_DIR` if elsewhere):

```bash
mvn -T 8 clean package -DskipTests -pl cortex/container,cortex/cli -am
( cd cortex/container && ./build-container.sh )                 # → metaloom/cortex-server:latest
docker run -d --name cortex-demo --network dev -p 8093:8093 \
  -e LOOM_HOST=loom -e LOOM_PORT=8092 metaloom/cortex-server:latest
```

It registers within a few seconds (verify: `GET /api/v1/processors` returns a `nodeId`). Then re-run the
capture script (or just the Cortex step) so `cortex.png` shows the online worker with its metrics and node
whitelist.

### 4. Tear down

```bash
docker rm -f loom postgres-demo cortex-demo
```

## Content Conventions

### Front matter

YAML front matter delimited by `---` at the top of each `.adoc`:

```yaml
---
title: Getting Started      # page title (rendered as <h1> by the docs layout)
weight: 1                   # optional: lower sorts first within a section
---
```

Only `title` is required; `weight` orders siblings (used by `getting-started` to pin it first).

### AsciiDoc body

* Pages are AsciiDoc; use AsciiDoc syntax (`== Heading`, `[source,bash]----...----`, `|===`
  tables, `link:target[Label]`, admonitions like `[TIP]`).
* **Internal links use relative AsciiDoc `link:` targets that resolve to Hugo pretty URLs**,
  e.g. `link:../loom/authentication/[Authentication]` and `link:rest-api[REST API]`. Keep the
  trailing-slash pretty-URL style consistent with existing pages.
* **Diagrams are inline SVG, not ASCII art** — see [Hand-drawn figures](#hand-drawn-figures).
* Rich landing/section layout (card grids, note boxes) is done with **raw HTML passthrough
  blocks** `++++ ... ++++` embedding Bootstrap markup + theme CSS classes (`docs-card`, `note`,
  `row`, `col-*`). See `docs/_index.adoc` for the canonical pattern.
* Shared attributes live in `docs/variables.adoc-include` (`:icons: font`,
  `:source-highlighter: prettify`, `:toc:`). The `.adoc-include` extension keeps Hugo from
  rendering it as a standalone page.
* Some pages open with a level-0 title (`= Title`) in the body in addition to front-matter
  `title:` (e.g. `interaction/`); prefer the front-matter title and level-2 (`==`) sections for
  new pages to match the docs layout, which already emits the `<h1>` from `title`.

### Legal pages (`docs/legal/`)

The legal section answers two questions a customer asks before deploying: *what am I allowed to run*
and *how was this built*.

* `legal/model-licenses/` is an **inventory of what each node loads**, not a generic license page.
  MetaLoom ships no weights — every model is a configuration value (`WhisperOptions.modelPath`,
  `VlmNodeOptions.model`, `FacedetectNodeOptions.inspirefacePackPath`, `ORPHEUS_REPO_DE`,
  `LOOM_AI_MODEL_ID`, …), so the page maps node → default model → license → commercial-use verdict,
  and closes with how to read the deployed values back.
* **Two components are non-commercial** and are called out in `[WARNING]` blocks under
  `#restricted`: the **InspireFace model packs** used by `facedetect` (code is Apache-2.0, but the
  packs inherit the InsightFace terms — *academic use only*, which also taints `facedescription`
  downstream) and **Ideogram 4.0**, the backing model of the planned `imagegen` node (weights under
  the *Ideogram 4 Non-Commercial Model Agreement*; see `spec/plans/imagegen-node.md`).
* **Conditionally licensed** entries live under `#conditional`: the Gemma defaults (`gemma2:27b` in
  `LLMNode`, `gemma3:27b-it-q8_0` in `FacedescriptionNode`) carry the Gemma Terms of Use, and the
  German TTS checkpoint `SebastianBodza/Kartoffel_Orpheus-3B_german_natural-v0.1` is a gated
  Llama-3.2 derivative (ungated Apache-2.0 swap: `Thorsten-Voice/tv-orpheus-v1`).
* `#clean-stack` gives the configuration that stays inside permissive licenses; `#runtimes` covers
  the native libraries redistributed in the container image, including the **FFmpeg** caveat (upstream
  LGPL-2.1+, but distro builds are often `--enable-gpl`).
* `legal/ai-disclosure/` states the timeline: **2023–2025 no AI code generation, 2026 onwards
  AI-assisted**. AI assistance is *not* tracked per commit — the disclosure is at project level, and
  the page says so rather than implying commit-level provenance exists.

> **Keep the inventory honest.** When a node's default model changes, or a node gains/loses a model
> dependency, update `docs/legal/model-licenses/` in the same change. Claims must reflect what the
> code actually loads (the whisper node runs **whisper.cpp locally** — it does not call a remote ASR
> endpoint, even though `asr4j` supports one). The page carries an explicit *not legal advice*
> disclaimer; do not let it drift into legal advice.

Both pages are linked from the docs landing card grid, the "Choose Your Path" table and a **footer
link row** rendered by `themes/meghna-hugo/layouts/partials/footer.html` (labels come from
`i18n/en.yaml`: `documentation`, `legal`, `modelLicenses`, `aiDisclosure`).

### Landing page (data-driven)

The home page (`themes/meghna-hugo/layouts/index.html`) is assembled from **partials**
(`banner`, `about`, `feature`, `cta`, `service`, `skill`, `team`, `funfacts`, `pricing`,
`testimonial`, `blog`, `contact`, `map`). Each partial reads its copy from a matching
`data/en/<name>.yml` file — **edit the YAML, not the partial**, to change landing-page text.
Several partials (pricing, portfolio, testimonial, contact, map) are wired in the layout but
their menu entries are commented out in `config.toml`.

Items in `data/en/feature.yml` (both `feature_item` and `feature_item_ops`) accept an optional
`link:` — a site-relative path to the docs page covering that feature (anchors allowed, e.g.
`/docs/loom/features/#_permissions`). When present, `partials/feature.html` turns the item title
into a link and appends a small "Read the docs →" affordance; items without a doc page (the
`(planned)` ones such as S3 or Import/Export) simply omit the key. Styling lives in
`less/includes/custom.less` (`.feature-doc-link`, mirrored into the compiled `assets/css/main.css`).

## Theme & Layouts

`themes/meghna-hugo/` is a vendored, customized copy of the Meghna Hugo theme.

| Layout | Applies to | Notes |
| --- | --- | --- |
| `layouts/docs/single.html` | leaf docs pages | 3-col: sticky TOC sidebar (`#toc`, bootstrap-toc) + `<h1>{{.Title}}</h1>` + `{{.Content}}`. |
| `layouts/docs/list.html` | docs section pages (`_index.adoc`) | centered wide column, no sidebar. |
| `layouts/index.html` | home page | partial pipeline described above. |
| `layouts/_default/*` , `layouts/author/*` | blog / fallback | article/list/single/baseof. |

The `docs` layout family is selected because pages live under the top-level `docs/` section.
Theme CSS is compiled from `themes/meghna-hugo/less/main.less` via the theme's `yarn build`.

### Plugins (config.toml)

CSS/JS plugins are declared in `config.toml` under `[[params.plugins.css]]` / `[[params.plugins.js]]`:
Bootstrap, FontAwesome5, Themify icons, slick, magnific-popup, lazy-load, **bootstrap-toc**
(docs TOC) and **Swagger UI** (`plugins/swagger/*`) used to embed the live Loom REST API.

## Configuration / Settings

The site has no application runtime; "configuration" means `config.toml` keys and build-time
env. Hugo `getenv` is restricted (`config.toml [security.funcs] getenv = ['^HUGO_', '^CI$']`),
so only `HUGO_*` and `CI` env vars are readable from templates.

| Setting (config.toml) | Default | Purpose |
| --- | --- | --- |
| `baseURL` | `https://metaloom.io` | Canonical site URL used for absolute links. |
| `title` | `MetaLoom` | Site title. |
| `theme` | `meghna-hugo` | Active theme directory under `themes/`. |
| `publishDir` | `dist` | Build output directory (published site root). |
| `paginate` | `6` | Blog list page size. |
| `summaryLength` | `15` | Words in auto-generated post summaries. |
| `enableRobotsTXT` | `true` | Emit `robots.txt`. |
| `disableLanguages` | `[]` | Languages turned off (none). |
| `discordLink` | `https://discord.gg/NFdnFcSbfA` | Community link. |
| `[security.exec] allow` | includes `asciidoctor` | External binaries Hugo may run — **must include `asciidoctor`**. |
| `Languages.en.contentDir` | `content/english` | Where English content is read from. |
| `[[Languages.en.menu.main]]` | features, developer, blog, docs | Top navigation entries + weights. |
| `params.logo` | `images/logo_word_big.svg` | Header logo asset. |

| Build/publish env | Where | Notes |
| --- | --- | --- |
| `HUGO_*`, `CI` | build env | Only env vars templates may read (security allowlist). |
| Node/Yarn | theme build | Installs theme deps + compiles CSS. |
| `asciidoctor` on PATH | Hugo runtime | Renders all `.adoc` content. |

## Publishing Flow

The Hugo repo is built; a **separate** repo publishes it via GitHub Pages.

1. In `metaloom/website/`, run `./build.sh` → produces `website/dist/`.
2. In the sibling `metaloom-website` repo, run `./pull.sh`:
   ```bash
   rm -rf docs
   cp -ra ../metaloom/website/dist docs
   ```
   i.e. it wipes `docs/` and copies the freshly built `dist/` into it.
3. Commit & push `metaloom-website` — GitHub Pages serves `docs/` at the `metaloom.io` domain
   (`docs/CNAME` = `metaloom.io`; the same `CNAME` and a `.nojekyll` marker are shipped from
   `website/static/`).

Because `pull.sh` deletes and recreates `docs/`, never hand-edit the staging repo's `docs/` —
change the Hugo source and rebuild.

## Key Files Reference

| File / dir | Purpose |
| --- | --- |
| `website/config.toml` | Site config: baseURL, theme, menu, plugins, security exec allow, params. |
| `website/build.sh` | Theme CSS build (yarn) + `hugo`. |
| `website/watch.sh` | Local preview server. |
| `website/content/english/docs/_index.adoc` | Docs landing (card grid, reading order, concepts). |
| `website/content/english/docs/**/index.adoc` | Individual customer doc pages (AsciiDoc). |
| `website/content/english/docs/variables.adoc-include` | Shared AsciiDoc attributes. |
| `website/content/english/blog/*/index.adoc` | Blog posts. |
| `website/data/en/*.yml` | Landing-page section copy (edit these, not partials). |
| `website/i18n/en.yaml` | UI string translations. |
| `website/static/` | Verbatim assets: `images/`, `CNAME`, `.nojekyll`. |
| `website/static/docs/examples/openapi.{json,yaml}` | Staged OpenAPI spec — downloadable and rendered by the API explorer. |
| `website/themes/meghna-hugo/static/plugins/swagger/swagger.js` | Swagger UI bootstrap + explorer options. |
| `website/themes/meghna-hugo/layouts/docs/*.html` | Docs page/section templates. |
| `website/themes/meghna-hugo/layouts/index.html` | Home-page partial pipeline. |
| `website/pom.xml` | Maven module registration (no build logic). |
| `metaloom-website/pull.sh` (sibling repo) | Copies `dist` → staging `docs/` for GitHub Pages. |

## Where do I find …?

| I want to … | Look at |
| --- | --- |
| Add/edit a customer doc page | `website/content/english/docs/<section>/index.adoc` |
| Add/edit a task-oriented guide | `website/content/english/docs/playbooks/<name>/index.adoc` (link it from `playbooks/_index.adoc` **and** `docs/_index.adoc`) |
| Add a new docs section | New folder under `docs/` with `_index.adoc` (section) + child `index.adoc` pages; link it from `docs/_index.adoc` |
| Change landing-page text | `website/data/en/<section>.yml` |
| Change top navigation | `[[Languages.en.menu.main]]` blocks in `config.toml` |
| Change UI labels ("Read more", menu names, footer links) | `website/i18n/en.yaml` |
| Record which model a node uses and its license | `website/content/english/docs/legal/model-licenses/index.adoc` |
| State how the code was produced (AI disclosure) | `website/content/english/docs/legal/ai-disclosure/index.adoc` |
| Change the footer link row | `website/themes/meghna-hugo/layouts/partials/footer.html` + `i18n/en.yaml` |
| Change docs page layout / TOC | `website/themes/meghna-hugo/layouts/docs/single.html` (+ `list.html`) |
| Add global CSS/JS plugin | `[[params.plugins.css]]` / `[[params.plugins.js]]` in `config.toml` |
| Change site colors/styles | `website/themes/meghna-hugo/less/` (rebuild via `build.sh`) |
| Add images to a page | Put them in the same page-bundle folder as the `.adoc` |
| Change the published domain | `website/static/CNAME` (and staging `docs/CNAME`) + `baseURL` |
| Fix "asciidoc renders empty" | Ensure `asciidoctor` installed and allowed in `[security.exec]` |
| Understand how the site is published | [Publishing Flow](#publishing-flow) / `metaloom-website/pull.sh` |
| Refresh the OpenAPI spec / API explorer | [The OpenAPI spec](#the-openapi-spec-download--api-explorer) — regenerate in `loom/doc`, then re-stage into `website/static/docs/examples/` |

## Conventions and Gotchas

* **Two `metaloom-website` things exist.** The Hugo source (`website/`, Maven artifactId
  `metaloom-website`) vs. the sibling publish repo (also named `metaloom-website`). Build in the
  former, publish from the latter.
* **AsciiDoc, not Markdown.** Docs/blog are `.adoc`. A missing `asciidoctor` binary silently
  yields empty pages. Blog `.adoc` and docs share the `variables.adoc-include` attributes.
* **`dist/` and `docs/` are generated + git-ignored.** Never commit build output to this repo;
  never hand-edit the staging repo's `docs/` (overwritten by `pull.sh`).
* **Legacy stub pages exist under `docs/`.** `docs/rest/`, `docs/test/` ("Administration Guide")
  and top-level `docs/configuration/` are old placeholder pages **not linked** from
  `docs/_index.adoc`. The maintained equivalents are `docs/loom/rest-api/`,
  `docs/loom/configuration/` and `docs/cortex/configuration/`. Prefer editing/consolidating into
  the maintained pages; the stubs are candidates for removal.
* **`content-off/` is parked content.** It is outside `contentDir` (`content/english`) and is
  not built. Use it as a place to disable a page without deleting it.
* **Landing page is data-driven.** Edit `data/en/*.yml`; editing the partial HTML usually isn't
  needed. Several sections are wired but hidden (menu entries commented out).
* **Pretty-URL relative links.** Internal `link:` targets rely on Hugo pretty URLs with trailing
  slashes (`link:../loom/authentication/[...]`). Match the surrounding style or links break.
* **Raw-HTML passthrough for layout.** Card/grid/note UIs are Bootstrap markup inside `++++`
  blocks bound to theme CSS classes; `[security] enableInlineShortcodes = false`, so don't rely
  on shortcodes for these.
* **Docs section auto-detection.** Pages get the `docs/` layouts purely because they live under
  the top-level `docs/` section — moving a page out of `docs/` changes its template.
* **MetaLoom ships no model weights.** Nodes name models by path, repo id or endpoint URL. Any
  license statement on the site is about a model *you* supply, which is why
  `docs/legal/model-licenses/` phrases every row as "default, configurable" and carries a *not legal
  advice* disclaimer. Two entries are hard blockers for commercial use (InspireFace packs, Ideogram
  4.0) — do not soften or drop those `[WARNING]` blocks.

## Test Setup

There is no unit/integration test suite for the website; verification is build + visual review.

1. Install prerequisites (Hugo extended, Node/Yarn, `asciidoctor`).
2. From `website/`, run `./watch.sh` (or `./build.sh` for a one-shot build).
3. Open `http://localhost:1313/` and verify:
   * Landing page renders all enabled sections.
   * `/docs/` landing card grid renders and links resolve.
   * A representative doc page (e.g. `/docs/loom/rest-api/`) renders with sidebar TOC and, for
     the REST API page, the embedded Swagger UI loads.
4. Confirm `dist/` is produced with no Hugo errors (missing `asciidoctor` is the usual failure).
5. Sanity-check internal links (no 404s) after adding/moving pages.
6. Dry-run publish: from the sibling `metaloom-website` repo, `./pull.sh` then inspect `docs/`
   (do not push unless intending to release).

## Progress Assessment

Current state of the website (as of the checkout below):

- [x] Hugo site scaffolding (config, theme, build/watch scripts, Maven module)
- [x] Data-driven landing page (partials + `data/en/*.yml`)
- [x] Docs landing page with card grid, reading order and concept map (`docs/_index.adoc`)
- [x] Getting Started guide (demo container, Loom UI, Loom App)
- [x] Loom docs: REST API, Java client, authentication, configuration, features, metrics, artifacts,
      maven-artifacts, containers, helm-chart, examples
- [x] Cortex docs: nodes, configuration, monitoring, metrics, artifacts, maven-artifacts,
      containers, examples
- [x] Shared conceptual docs: operation model, pipeline mechanism
- [x] Container Images page (`docs/deployment/`) with the full image + port inventory
- [x] Chat & AI Agent docs (`docs/loom/chat/`) — agentic loop, Sessions, Skills, Memory, coding sandbox
- [x] Loom UI docs (`docs/ui/`) — dark-mode screenshot tour of every UI area, a new "Loom UI" card on the
      docs landing grid, and a reproducible capture procedure (`loom-ui/scripts/capture-ui-screenshots.mjs`)
- [x] UI docs follow the AI / Content / Management navigation (ACL sub-group), and cover Chat Sessions,
      Tasks and Monitoring; asset screenshots show real image previews from the seeded binaries
- [x] Agentic Coding Sandbox deployment (`docs/loom/agent-sandbox/`) — podman/k8s backends, RBAC, config
- [x] Cortex docs updated to the daemon-that-serves-nodes model (Loom owns the DAG); ports use `8092`
- [x] Cortex examples cover a custom node (Java), a custom daemon (Java) and a custom worker (Python)
- [x] REST API page extended with pipelines run/versions, processors, chats+stream, sessions, skills,
      memory, and the `json-comps`/`node-results` persistence endpoints
- [x] OpenAPI regeneration documented (see "Updating the embedded OpenAPI spec")
- [x] Fixed a generation artifact in `loom/maven-artifacts/index.adoc` (stray `*** Add File:` blob
      duplicating containers/helm content with the old `metaloom/loom:latest` image name)
- [x] Swagger UI plugin wired for the Loom REST API — mount point on `docs/loom/rest-api/`,
      site-relative spec URL, no-op on pages without `#swagger-ui`
- [x] Staged `openapi.json` at `website/static/docs/examples/openapi.json`
- [x] GraphQL API page (`docs/loom/graphql-api/`) with an embedded **GraphiQL** explorer — schema
      built in-browser from the staged `schema.graphql` (offline, execution disabled by default),
      `plugins/graphiql/*` wired like Swagger, plus a live GraphiQL served by the loom server at
      `/graphiql`
- [x] Complete, explorable OpenAPI spec on the REST API page — the generator now covers **all**
      endpoints (~130 paths / 35 resource tags, incl. the chat and memory endpoints of the agent
      modules) with `{uuid}` path templating, declared path/query parameters, tags, security
      schemes, standard error responses and inlined JSON examples
- [x] Spec offered for **download** (`openapi.yaml` / `openapi.json` cards) next to the embedded
      **API Explorer** (filter, deep links, collapsed-by-default tag groups, readable on the dark
      site theme)
- [x] Server serves the same document at `/api/v1/openapi`, `/openapi.yaml` and `/openapi.json`
      with its own address as the server URL
- [x] Playbooks section (`docs/playbooks/`) — Docker deployment, Kubernetes deployment (service
      account, sandbox RBAC/quota/NetworkPolicy, Helm packaging), and three pipeline playbooks
      (transcription for the chat agent, scene-level video analysis, translation). Linked from the docs
      landing card grid, reading order and path table
- [x] Corrected `docs/loom/helm-chart/` — the page previously documented `helm upgrade --install ./loom/helm`
      although `loom/helm` contains only a README; it now states that status and points at the Kubernetes
      playbook
- [x] Explicit anchors added where cross-page links needed them (`loom/chat/`: `#coding-sandbox`,
      `#memory`, `#skills`, `#example-skill-transcript-summarizer`; `nodes/`: `#requirements`) — the
      existing `#coding-sandbox`/`#memory` links were pointing at Asciidoctor's auto-generated
      `_coding_sandbox`/`_memory` ids and did not resolve
- [x] Legal & Licensing section (`docs/legal/`) — a **Model Licenses** page inventorying every model,
      runtime and native library the built-in nodes load, with a commercial-use verdict per entry and
      `[WARNING]` call-outs for the two non-commercial components (**InspireFace model packs** for
      `facedetect`, **Ideogram 4.0** for the planned `imagegen`), the conditionally licensed ones
      (Gemma terms, gated Llama-3.2 Kartoffel TTS checkpoint), a clean-commercial-stack recipe and a
      table of where each model id is configured
- [x] **AI Code Generation Disclosure** page (`docs/legal/ai-disclosure/`) — 2023–2025 hand-written,
      2026 onwards AI-assisted; scope, review/ownership, and the statement that Apache-2.0 and the
      runtime model licenses are unaffected
- [x] Legal section wired into the docs landing card grid, the "Choose Your Path" table and a new
      footer link row (`partials/footer.html` + `i18n/en.yaml`)
- [x] Custom-node playbook (`docs/playbooks/python-node/`) — Python worker over the wire protocol, the
      two registrations a custom kind needs (Loom-side node descriptor + the kind the worker advertises),
      persistence path and packaging. Playbooks contain no Java sources by design; the translation
      playbook's translate step points here
- [x] Playbook figures are inline SVG in the `docs/operation/` house style (no ASCII art)
- [x] `docs/playbooks/python-node/` carries a paste-ready **coding-agent prompt** that generates the
      whole worker (wire protocol, node contract, persistence, deliverables, definition of done) plus a
      review checklist of the predictable failure modes. Keep the prompt in sync when the processor
      protocol or the node-result endpoints change — it duplicates those facts on purpose so an agent
      without repo access can still produce a correct worker
- [x] Prompt hardened after a real generation run (`workspaces/metaloom/custom-node`, an ffprobe-based
      `media-probe` worker). What the first version let through: the wire state `COMPLETED` posted to
      `/node-results`, whose column CHECK only accepts `SUCCESS|FAILED|SKIPPED`; a missing
      `producerVersion`; no JWT-expiry handling; no advertised-vs-implemented kind check. All four are
      now explicit in the prompt, the § "Persist the Result" step and the review checklist
- [x] Corrected the login endpoint across the docs: it is `POST /api/v1/login` (`LoginEndpoint`), not
      `/api/v1/auth/login` as `docs/loom/authentication/` and the first playbook draft claimed
- [x] Legal & Licensing landing page leads with a prominent **Apache 2.0** section — what the license
      permits, what it covers, and where it stops (model weights, third-party runtimes)
- [x] Blog section with initial posts
- [x] GitHub Pages publish flow via sibling `metaloom-website` repo (`pull.sh`, CNAME)
- [ ] Remove/consolidate legacy stub pages (`docs/rest/`, `docs/test/`, top-level
      `docs/configuration/`) into the maintained Loom/Cortex pages
- [ ] Automate the staging of `loom/doc/src/main/generated/openapi.*` into `website/static/` — it is
      still a manual `cp` after every `ExampleGenerator` run
- [ ] Document request/response **schemas** in the spec (it currently carries examples only, so
      generated clients get untyped bodies) — see `spec/loom/RESTAPI.md` §7.5
- [ ] Describe the two WebSocket endpoints in the spec (OpenAPI 3.0 cannot express them; they are
      documented in `spec/loom/WEBSOCKET.md` only)
- [ ] Only load the ~1 MB Swagger UI bundle on the REST API page instead of globally
- [ ] Fill remaining thin pages (e.g. `helm-chart`) with full content
- [ ] Automate build+publish (currently manual `build.sh` + `pull.sh` + push)
- [ ] `examples/cortex-python/daemon.py` posts the wire state (`COMPLETED`) to `/assets/:uuid/node-results`,
      which the `asset_node_result_state_check` constraint rejects — the ledger row is lost while the
      json-comp still lands. It also never sends `producerVersion`. Fix the example (map to `SUCCESS`,
      stamp a version); the playbook currently warns readers about it instead
- [ ] Revisit the playbooks' "node availability" caveat once `PipelineNodeFactoryModule` registers the
      remaining kinds (`whisper`, `llm`, `ocr`, `tika`, `facedetect`, `captioning`, `scene-detection`,
      `quality`, `consistency`, dedup, `loom`, `filter-*`) — the stock worker currently advertises only
      `filesystem-source`, `asset-source`, the hash kinds, `thumbnail`, `vlm` and `tts`
- [ ] `docs/nodes/llm/` claims upstream outputs "can be referenced by prompts"; `LLMNode` only binds the
      asset filename into the prompt. Fix the page (or the node) — the translation playbook documents the
      code behaviour
- [ ] Revisit the `imagegen` row in `docs/legal/model-licenses/` once the node actually lands (see
      `spec/plans/imagegen-node.md`) — it currently documents a *planned* node and its non-commercial
      Ideogram 4.0 weights
- [ ] Keep `docs/legal/model-licenses/` in sync with node model defaults (ongoing — the page is only
      useful if it matches what the code loads)
- [ ] Keep customer docs in sync with product specs under `spec/` (ongoing)

---

_GIT HEAD: `5fbbeebc24506e5bca815fb759e2440d0ff6e56a` (branch `master`)_
_Generated: 2026-07-26 (UTC)_
