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
* The **home page** is short by design and routes readers two ways — see
  [The home page](#the-home-page). Five top-level areas besides the docs: **`/tour/`** (the
  design-led product tour), **`/studio/`** (the commercial edition — a second, warm-accented
  scroller), **`/features/`** (the full list), **`/announcements/`** (releases) and `/blog/`.
  ⚠️ `/tour/` **used to be `/studios/`**; it was renamed so it could not be confused with
  `/studio/`, and a Hugo alias redirects the old URL.
* Build with `website/build.sh` → output goes to `website/dist/` (`publishDir = "dist"`). The
  build fails on localhost links **and on broken internal links** (see
  [The build-output checks](#the-build-output-checks)).
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
| `./build.sh` | Full build: `cd themes/meghna-hugo && (yarn\|npm) install && … build` (compiles theme CSS), then `hugo` at the project root → writes `dist/`, then the two **build-output checks** (below). |
| `./watch.sh` | Runs `build.sh` then `hugo server -b http://localhost:1313/` for live local preview. |
| `hugo` | Site build only (assumes theme CSS already built). Output → `dist/`. |
| `node check-links.mjs [dist]` | The broken-link check on its own — useful while editing links without a full rebuild. |

### The build-output checks

`build.sh` ends with two checks over `dist/`. Both exit 1 and print every offending page, and both
are cheap enough to run on every build.

#### The localhost-link check

`build.sh` fails (exit 1) when the generated `dist/` contains a **link or resource attribute**
pointing at the build machine — `href`, `src`, `srcset`, `action`, `data-src` or the plugin
`data-*-url` attributes with a `localhost` / `127.0.0.1` / `0.0.0.0` / `[::1]` host. Such a URL makes
the published site send the reader's browser to their *own* machine (and triggers the browser's
Local Network Access prompt).

Mentioning a local address **in documentation text is fine** — the demo container is documented as
`localhost:8092` all over the docs. Only the attributes are rejected. The catch is that Asciidoctor
**auto-links a bare URL even inside backticks**, so `` `http://localhost:8092` `` still renders as an
`<a href>`. Suppress it with a leading backslash:

```asciidoc
The UI is at `\http://localhost:8092/ui/` — log in as `admin`.
```

The backslash is consumed by Asciidoctor; the rendered page shows `http://localhost:8092/ui/` as
plain monospace text. In a raw-HTML `++++` block, just write `<code>…</code>` instead of `<a href>`.

#### The broken-link check (`check-links.mjs`)

`website/check-links.mjs` (plain Node, no dependencies) walks every `*.html` in `dist/`, collects
the `href`/`src`/`srcset`/`action`/`poster`/`data-*-url` attributes a browser would follow and
verifies that each **internal** target is served by the build:

* Pretty URLs resolve the way the published site serves them — `/docs/`, `/docs` and `/docs.html`
  all map to a file if one exists.
* `#fragment` targets are checked against the `id=`/`name=` attributes of the target document, so a
  renamed heading is caught (that is what `#loom-ui` and `#loom-app` were).
* Absolute URLs to `metaloom.io` / `www.metaloom.io` are treated as internal; every other host is
  skipped — this is an offline consistency check, it never fetches the network.
* `mailto:`/`tel:`/`javascript:`/`data:` and bare `#` are ignored.

Run it directly with `node check-links.mjs` after editing links. Two failure shapes it catches
regularly:

1. **A relative `link:` without `../`.** `link:rest-api[REST API]` on `/docs/loom/graphql-api/`
   resolves to `/docs/loom/graphql-api/rest-api`, not `/docs/loom/rest-api/`.
2. **A child page that Hugo never built.** A directory holding `index.adoc` is a *leaf* bundle, so
   any subdirectory in it is a resource, not a page — `docs/deployment/helm/` produced no output
   until `docs/deployment/index.adoc` was renamed to `_index.adoc`. See the gotcha below.

`build.sh` uses `set -o errexit -o nounset` — a missing `asciidoctor`/`hugo`/`node` (or both `yarn`
**and** `npm`) fails the whole script. `dist/` and `docs/` are git-ignored in this repo (see
`website/.gitignore`), so a build never dirties the working tree with output.

### Maven integration

`website/pom.xml` is a `packaging=pom` module of the `metaloom-parent` reactor (artifactId
`metaloom-website`). It does **not** invoke Hugo — it exists so the website participates in the
Maven module graph/versioning. The real build is `build.sh`.

## Folder Structure

```
website/
├── config.toml            # Hugo site config: baseURL, theme, menu, plugins, params
├── build.sh               # theme CSS build + hugo + the two dist/ checks
├── check-links.mjs        # broken-internal-link checker (run by build.sh, also standalone)
├── watch.sh               # build + hugo server (local preview)
├── pom.xml                # Maven pom module (no build logic)
├── .gitignore             # ignores dist/, docs/, resources/, node_modules, target ...
├── content/
│   └── english/           # contentDir for the "en" language (config.toml)
│       ├── _index.md      # home-page front matter (title, description, page_css)
│       ├── docs/          # ★ CUSTOMER-FACING DOCUMENTATION (AsciiDoc)
│       ├── features/      # /features/ — full feature list, rendered from data/en/feature.yml
│       ├── tour/         # ★ /tour/ — design-led product tour (_index.md only; alias /studios/)
│       ├── studio/       # ★ /studio/ — MetaLoom Studio, the commercial edition (_index.md only)
│       ├── announcements/ # release announcements (_index.adoc + one bundle per release)
│       ├── blog/          # blog posts (one folder per post, index.adoc)
│       └── author/        # blog author pages
├── content-off/           # DISABLED content (not built; parked pages). e.g. an old POC post
├── data/en/*.yml          # page copy: home.yml (/), tour.yml (/tour/), studio.yml (/studio/),
│                          #   feature.yml (/features/). The other files are legacy Meghna
│                          #   sections, unused.
├── i18n/en.yaml           # UI string translations (menu labels, "Read more", etc.)
├── static/                # copied verbatim to dist/: images/, CNAME, .nojekyll, robots
│   ├── images/og-*.jpg    # 1200x630 social cards (see Social cards & page metadata)
│   └── docs/examples/openapi.{json,yaml}  # staged OpenAPI doc: downloadable + rendered by Swagger UI
├── resources/_gen/        # Hugo asset cache (git-ignored)
├── themes/meghna-hugo/    # vendored + customized theme (layouts, LESS, JS plugins)
│   └── assets/            # Hugo-processed assets: css/{main,home,tour,studio}.css,
│                          #   js/{script,reveal}.js, images/scenery/*.jpg
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
* `legal/impressum/` is the **Austrian site disclosure** and the only page on the site written in
  German, because that is the language the disclosure duty is discharged in. It covers § 5 ECG
  (operator, address, direct contact, register/UID/trade data, applicable law), § 25 MedienG
  (Medieninhaber, Unternehmensgegenstand, Blattlinie), copyright, liability for content and links,
  the EU ODR platform, and a `#datenschutz` section describing what the static site actually
  processes: GitHub Pages access logs, the Google Fonts CDN, external links, and email contact.

> **The Impressum still has placeholders.** The address and phone number are `[…]` markers, and an
> AsciiDoc comment at the top of the file lists exactly what has to be filled in. § 5 ECG wants a
> real geographic address and a *second* direct channel besides email — the page is not complete
> until those are real. The rows that assume "private project, no Firmenbuch, no UID, no trade
> licence" have to be revisited the moment MetaLoom is offered commercially.
>
> Two follow-ups it names but does not fix: the site loads **Google Fonts** from Google's CDN
> (which is what makes a privacy section necessary at all — self-hosting the two families would
> remove it), and the page is a good-faith template that has not been reviewed by a lawyer.

> **Keep the inventory honest.** When a node's default model changes, or a node gains/loses a model
> dependency, update `docs/legal/model-licenses/` in the same change. Claims must reflect what the
> code actually loads (the whisper node runs **whisper.cpp locally** — it does not call a remote ASR
> endpoint, even though `asr4j` supports one). The page carries an explicit *not legal advice*
> disclaimer; do not let it drift into legal advice.

All three legal pages are linked from the docs landing card grid, the "Choose Your Path" table and
the site footer — see [The site footer](#the-site-footer).

### The site header

`themes/meghna-hugo/layouts/partials/navigation.html`, styled in `less/includes/custom.less`
(`.navigation`, `.navbar-*`) because it is the same header on every page.

* **Sticky and translucent** — `rgba(17,21,26,.72)` plus `backdrop-filter: blur(16px)`, so the
  hero shows through it. A `@supports not (backdrop-filter)` fallback swaps in an opaque bar
  rather than leaving a washed-out one.
* **`.is-scrolled`** is toggled past 12 px of scroll by a small block at the end of
  `assets/js/script.js`; it darkens the bar, adds a shadow and shrinks the logo. Cosmetic only —
  the header is legible in either state.
* **The current section is marked.** The partial compares `.RelPermalink` against each menu
  entry's URL and adds `.is-active` (plus `aria-current="page"`), which shows as a teal underline
  on desktop and a left border on mobile. The underline is the same element that grows on hover.
* **Menu labels are the `name` values in `config.toml`** and are written capitalised (`Studios`,
  `Docs`) — no CSS text-transform.
* The **Discord icon** comes from `params.discordLink`. That key used to sit at the root of
  `config.toml` where templates could not read it; it now lives under `[params]`.
* The mobile toggler is a three-bar hamburger that folds into an X (`[aria-expanded="true"]`),
  and the open panel gets its own solid surface — translucency is fine for a 60 px bar over a
  hero, not for a full menu with page content behind it.

### Reading pages — docs, announcements, blog

The docs, `/announcements/` and `/blog/` share one surface (the theme's `#353b43`, deliberately
lighter than the marketing pages) and, since this pass, one typographic system, defined in the
*Reading pages* block of `less/includes/custom.less`:

* **Headings** — Quattrocento Sans, 700, `-.01em` tracking (the marketing pages' treatment at a
  size that suits a document). **Prose** — Anaheim, `1.03rem`/`1.78`, `#cdd6df`.
  **Technical values** — monospace.
* Before this, a single docs page mixed three fonts by accident: paragraphs rendered in
  Quattrocento Sans (from the theme's `p` rule), list items and table cells in Anaheim at the
  body's muted `#737f8a`. That mismatch — not the colour scheme — is what made the docs feel
  unlike the rest of the site. Table cells now carry the prose colour, and table headers are
  small uppercase teal labels on a tinted row.
* Inline `code` is a chip (subtle background + border) rather than only an orange colour change.
* **A shared page header** (`.page-head`): teal eyebrow, title, optional lead, hairline rule.
  Announcements, the blog overview and blog posts all use it; the copy comes from front matter
  (`eyebrow`, `subtitle`), never from the template — `_default/list.html` also renders `/author/`.
* **`body` is a flex column with `min-height: 100vh`** and `#content` grows, so a short page (the
  announcement list with one entry) no longer leaves the footer floating mid-viewport.
* The docs sidebar (page TOC + topic list) was left structurally alone — only its fonts were
  aligned. It is the one part of the docs that was already right.

Blog specifics: `_default/list.html` is the overview (copy from `content/english/blog/_index.md`),
`_default/article.html` is one card in the grid (image, date, title, summary — the whole card is
the link), `_default/single.html` is a post: docs-shaped sticky TOC, byline, hero image with
credit, and a *More posts* list. Styles are the `.blog-*` block in `custom.less`.

<a id="the-site-footer"></a>
### The site footer

`themes/meghna-hugo/layouts/partials/footer.html` renders the same footer under the marketing
pages and the docs, so it is styled site-wide in `less/includes/custom.less` (`.site-footer*`,
mirrored into the compiled `assets/css/main.css`) rather than in a page stylesheet.

Four columns — brand (logo, one-line description, the *1.0.0 — not released yet* badge linking to
the announcement), *Explore*, *Documentation*, *Project* — then a rule, the copyright line and the
contact pills.

* **Labels come from `i18n/en.yaml`**, not from the template.
* **The contact pills come from `[[params.social]]`** in `config.toml`: `icon` (Themify `ti-*` or
  FontAwesome `fab/fas`, both loaded globally), `label` (rendered *and* used as the accessible
  name) and `link`. They replaced a single placeholder Twitter icon that pointed at `#`.
* The **Impressum** link belongs here: the Austrian disclosure duty is per site, and this is what
  makes it reachable from every page.
* **Footer headings carry `data-toc-skip`, and `plugins/toc/toc.js` scopes bootstrap-toc to
  `.docs-main-content`.** With the default (body) scope the footer's column headings were
  collected into the docs sidebar TOC. Any new site chrome with headings needs the same care.

### Blog post images

A blog post is a page bundle whose front matter names its teaser image by **bare file name**
(`image:` = the jpg/svg original, `image_webp:` = the webp variant), co-located with `index.adoc`.
Three places render it — the overview cards (`_default/article.html`, used by both `/blog/` and the
landing-page blog section), the post hero (`_default/single.html`) and the social-card meta tags
(`partials/card.html`) — and all three resolve it through one helper:

```go-html-template
{{ partial "func/page-image.html" . }}   {{/* → site-relative URL, e.g. /blog/day3-…/foo.webp */}}
```

The partial prefers `image_webp`, falls back to `image`, then to `/images/banner_square.webp`, and
resolves a bare name against the page bundle (`.Resources.GetMatch`, then `.RelPermalink`). Pass its
result through `absURL` where an absolute URL is required (`og:image`, `twitter:image:src`).

> **Never concatenate an image name onto `.Permalink` in a template.** That was the original bug:
> `{{ .Permalink }}/{{ .Params.Image_webp }}` produced a **double slash** (a pretty-URL permalink
> already ends in `/`) *and* an absolute URL carrying whatever host the build resolved — which is how
> `http://localhost:1313/blog/day3-vertx-dagger-poc//christian-…webp` ended up in the overview cards.
> Prefer `.RelPermalink` / `relURL` for anything a browser fetches; reserve absolute URLs for
> canonical/OpenGraph metadata.

## The home page

The home page is a **short front door**, not a brochure: hero → pre-release notice → "two ways in"
→ what it is → stack strip → three latest posts. Everything longer lives elsewhere — the visual
tour on `/tour/`, the full feature list on `/features/`, the blog overview on `/blog/`, the
reference in `/docs/`. There is no closing "get started" pitch: the hero and the footer carry
those links already.

| Piece | Path |
| --- | --- |
| Front matter (title, description, `page_css`) | `content/english/_index.md` |
| **All copy** | `data/en/home.yml` |
| Layout | `themes/meghna-hugo/layouts/index.html` |
| Hero backdrop + door marks | `themes/meghna-hugo/layouts/partials/home/{art-weave,icon-visual,icon-technical}.html` |
| Styles | `themes/meghna-hugo/assets/css/home.css` (shared with `/features/`) |
| Motion | `themes/meghna-hugo/assets/js/reveal.js` (shared with `/tour/` and `/studio/`) |

Design intent, worth keeping:

* **It has to serve two visitors at once** — someone with an archive who does not care how it
  works, and someone who wants the API. That is what the *Two ways in* section does: one card to
  `/tour/`, one to `/docs/`, so neither reader is made to wade through the other's material.
  `/studio/` is deliberately **not** a third door — the home page routes visual vs. technical, and
  the commercial page is reached from the header and the footer instead.
  The "what it is" tiles reinforce it — a plain-language sentence plus a line of monospace chips
  (`19 node kinds`, `REST · GraphQL`) so both audiences find their own hook in the same tile.
* **The pre-release status is the second thing on the page** (and the first is the status pill in
  the hero). See the next section.
* The old Meghna landing sections (`about`, `service`, `skill`, `funfacts`, `pricing`,
  `testimonial`, `contact`, `map`, `banner`, `cta`, `blog`) are **no longer wired in**. The
  partials and their `data/en/*.yml` files are still in the theme but unused; only
  `home.yml`, `tour.yml`, `studio.yml` and `feature.yml` are live copy. Do not "fix" the old YAML expecting
  it to show up.

### The pre-release notice

MetaLoom is not released, and the site says so in four places: the warm status pill in the hero,
the *Not released yet* card below it, the announcement both link to, and the badge in the footer.

The card pairs the copy with a short **facts list** (`notice.facts` in `data/en/home.yml`) —
version in tree, published artifacts, demo container — rendered in monospace so it reads as a
status readout rather than a pitch. Keep those values true; they are the first thing a visitor
checks the project against. Three links that actually work sit next to it (announcement, blog,
Discord).

> An earlier revision had a deliberately disabled "Notify me" field here as a placeholder for a
> mailing list that does not exist. It was removed. If a real list ever appears, add the control
> *and* say plainly what happens to the address — never a field that looks inert but collects,
> or one that silently swallows what is typed into it.

### `/features/` — the full list

`/features/` renders `data/en/feature.yml` (both `feature_item` and `feature_item_ops`), so there
is still exactly **one** place to edit a feature. It is reached from the top navigation and from
the home page's "All features →".

* Each item takes an optional `link:` — a site-relative path to the docs page covering it (anchors
  allowed, e.g. `/docs/loom/features/#_permissions`) — rendered as a "Read the docs →" affordance.
* A title ending in `(planned)` is rendered as a **badge** next to the (stripped) name, so keep
  writing them that way in the YAML. The `(planned)` items are Image manipulation, Import/Export
  and S3; CLI and GraphQL lost that marker because both ship.
* `title`/`content` and `title_ops`/`content_ops` are the two group headings and their intro lines.

<a id="the-studios-page"></a>
## The /tour/ page (formerly `/studios/`)

`/tour/` is the **non-technical entry point**: a long, dark, image-led scroller aimed at media
studios, archives and creators, in contrast to the reference-style docs. It is linked from the top
navigation as *Tour* (`config.toml`, weight 2).

> **It was `/studios/` until 2026-07-28.** The plural page and the new commercial `/studio/` page
> would have been one character apart, so the tour moved to `/tour/` — content dir, data file,
> layout dir, art partials and stylesheet all renamed with it, and the photography folder became
> the neutral `assets/images/scenery/` because both pages draw from it. The old URL is kept alive
> by `aliases: ["/studios/"]` in `content/english/tour/_index.md`. See
> [The /studio/ page](#the-studio-page) and [Aliases](#aliases-redirects).

| Piece | Path | Role |
| --- | --- | --- |
| Content stub | `content/english/tour/_index.md` | Front matter only — `title`, `description`, `page_css: css/tour.css`, `aliases: [/studios/]`. No body. |
| Copy | `data/en/tour.yml` | **All text.** Hero, problem, three steps, six capability panels, the numbers strip, the sovereignty and audience cards, the closing CTA. |
| Layout | `themes/meghna-hugo/layouts/tour/list.html` | Section order, image processing, the inline `st-js` bootstrap. |
| Art | `themes/meghna-hugo/layouts/partials/tour/art-*.html` | One partial per illustration (inline SVG / small markup + CSS). |
| Styles | `themes/meghna-hugo/assets/css/tour.css` | Plain CSS (custom properties), everything prefixed `.st-*` (the prefix was **not** renamed — `.st-` is the tour's vocabulary, `.sd-` is Studio's). **Not** compiled from LESS. |
| Motion | `themes/meghna-hugo/assets/js/reveal.js` | Shared with the home page — see [Scroll reveal](#scroll-reveal-shared). |
| Photography | `themes/meghna-hugo/assets/images/scenery/*.jpg` | Four abstract light-streak Unsplash shots, resized to webp by Hugo at build time. Shared with `/studio/`, which uses the fourth (`spectrum.jpg`). |

Rules to keep when editing it:

* **Change copy in the YAML, not the layout.** Each panel's `art:` key selects its partial by name
  (`art: faces` → `partials/tour/art-faces.html`) through
  `{{ partial (printf "tour/art-%s.html" $p.art) $p }}`, so a new panel needs a partial with a
  matching file name or the build fails.
* **The stylesheet is page-scoped.** `partials/head.html` emits a `<link>` only for pages that
  carry `page_css: <asset path>` in front matter. That mechanism is generic — any future bespoke
  page can use it — `/studio/` uses the same mechanism for `studio.css`.
* **`tour.css` is hand-written CSS.** The theme's `yarn build` only compiles `less/main.less`;
  do not expect a LESS rebuild to touch it.
* **Never hide content behind JavaScript** — see [Scroll reveal](#scroll-reveal-shared).
* **All motion is decoration.** The `prefers-reduced-motion` block at the end of `tour.css`
  disables every animation and transition on the page, so nothing may encode information in
  movement alone.
* **No CJK text.** The site ships no CJK webfont; the translation panel deliberately uses
  Latin-script languages only, because a Japanese line renders as tofu boxes on machines without a
  system fallback font.
* Images go through `.Fill "<w>x<h> webp q<n> Center"`, which turns the 1–1.5 MB source JPEGs into
  15–95 KB webp files. Add new photography to `themes/meghna-hugo/assets/images/scenery/`, not to
  `static/`, or it will be published unprocessed.

### The hero: travelling light and the bottom fade

The hero photograph is a bundle of colour bands sweeping from the lower left to the upper right.
Five layers sit inside `.st-hero-media`, and the order is the design:

| Layer | What it does |
| --- | --- |
| `img` | the photograph, with a 26 s scale/translate drift |
| `.st-hero-pulse` ×3 | gradient stripes laid **across** the band axis and slid **along** it, blended into the photo — the "energy passing through the bands" effect |
| `.st-hero-breathe` | a slow radial swell of teal (`opacity` only) — the "pulsate" half |
| `.st-hero-veil` | darkens the left side so the headline stays legible |
| `.st-hero-fade` | the bottom 46 %, fading to `--st-bg` so the bands run out instead of being cut off |

Rules for touching it:

* **Angles follow the picture.** `35deg` is the direction the bands run, so a gradient at that
  angle puts the stripe edges at right angles to them and the transform slides the stripe along
  them. The third layer runs the other way (`-58deg`) at lower opacity so the motion does not read
  as one flat wipe. If the photograph is ever replaced, re-measure the band angle.
* **Only `transform` and `opacity` are animated**, and `.st-hero-media` carries
  `isolation: isolate` so the `mix-blend-mode` layers blend into the photo and not into the page.
  Do not animate `filter` or `background-position` here — that would repaint a full-bleed image
  every frame.
* The pulses live **under** the veil on purpose. Above it they would brighten the headline area.
* `prefers-reduced-motion` freezes them at a fixed opacity rather than hiding them.

<a id="the-studio-page"></a>
## The /studio/ page — MetaLoom Studio (commercial)

`/studio/` is the **commercial pitch**: the same kind of dark scroller as `/tour/`, aimed at the
person who has to sign something. It is linked from the top navigation as *Studio*
(`config.toml`, weight 4) and from the footer's *Explore* column.

> **What it claims is a proposal, not a shipped product.** The monetisation options, the open
> decisions behind them and the mapping from each claim on the page back to its decision live in
> [../METALOOM_STUDIO_PLAN.md](../METALOOM_STUDIO_PLAN.md) § "What The Website Currently Claims".
> Change the page and that section together, or the two drift.

| Piece | Path | Role |
| --- | --- | --- |
| Content stub | `content/english/studio/_index.md` | Front matter only — `title`, `description`, `page_css: css/studio.css`. No body. |
| Copy | `data/en/studio.yml` | **All text.** Hero, the open-core ledger and its three rules, six capability panels, the numbers strip, the editions table rows, audience cards, the early-access CTA. |
| Layout | `themes/meghna-hugo/layouts/studio/list.html` | Section order, image processing, the editions `<table>`. |
| Art | `themes/meghna-hugo/layouts/partials/studio/art-*.html` | One partial per illustration: `ledger`, `identity`, `storage`, `licensing`, `operations`, `support`, `integrations`. |
| Styles | `themes/meghna-hugo/assets/css/studio.css` | Plain CSS, everything prefixed `.sd-*`. **Not** compiled from LESS. |
| Motion | `themes/meghna-hugo/assets/js/reveal.js` | Shared — see [Scroll reveal](#scroll-reveal-shared). |
| Photography | `themes/meghna-hugo/assets/images/scenery/*.jpg` | Shared with `/tour/`; the hero is `spectrum.jpg`, which `/tour/` does not use. |

Rules to keep when editing it:

* **It must not look like `/tour/`.** The two pages share structure, the reveal vocabulary and the
  photography folder on purpose, but the accent is the deciding difference: `/tour/` is teal
  (`#57cbcc`), `/studio/` is amber (`#e2a86e`, the site's warm colour). Inside `studio.css` teal
  survives as `--sd-teal` and marks exactly one thing — **what is open source** (the left column of
  the ledger, the Community ticks in the editions table). Do not spend it on anything else.
* **Prefixes are the isolation mechanism.** `/tour/` owns `.st-*`, `/studio/` owns `.sd-*`, and
  each stylesheet is page-scoped through `page_css`. Nothing is shared between the two files except
  the `.reveal` contract, which both restate.
* **Change copy in the YAML, not the layout** — same rule as `/tour/`, same `art:`-key-to-partial
  mapping (`art: storage` → `partials/studio/art-storage.html`).
* **The editions comparison is a real `<table>`.** It is comparison data, so it stays a table with
  `<th scope=…>`, a `<caption class="sr-only">` and an `.sr-only` "included"/"not included" next to
  every ✓/– glyph — the state must never be carried by colour or a glyph alone. It sits inside
  `.sd-table-scroll` (`overflow-x: auto`) with a `min-width` on the table, so it scrolls in its own
  box and the page body never scrolls sideways.
* **The illustrations hold `white-space: nowrap` runs** (the audit line, the group→role rows, the
  image digest). A grid track of `1fr` is `minmax(min-content, 1fr)`, so without
  `.sd-split > * { min-width: 0 }` those runs refuse to shrink and the whole panel is clipped by
  `.sd-page`'s `overflow-x: hidden` on a phone. That rule is load-bearing — if a new illustration
  introduces another nowrap run, re-check 420 px (see [Test Setup](#test-setup)).
* **Two promises on the page are load-bearing**: *nothing that ships open source moves into Studio*
  and *Studio does not meter processing*. They are the reason the page is credible; do not soften
  them here and do not contradict them on `/features/` or in the docs.
* **No prices, and no form.** Pricing is "announced with 1.0.0" until it is decided, and the CTA is
  a `mailto:` — there is no mailing list, and a field that looks inert but collects (or collects and
  does nothing) is exactly what the home page's status card was cleaned up to avoid.
* **Numbers in the art are illustrative.** The response times in `art-support.html` are examples and
  the partial says so in a comment; real figures belong in a contract, never on a marketing page.
  The license rows in `art-licensing.html` mirror `docs/legal/model-licenses/` — if a default model
  or its license changes, change both in the same pass.
* **All motion is decoration.** The `prefers-reduced-motion` block at the end of `studio.css`
  disables every animation and transition on the page.

<a id="aliases-redirects"></a>
## Aliases (redirects)

A page can keep an old URL alive with `aliases:` in its front matter. Hugo then emits a small
redirect document at each old path — that is how `/studios/` still resolves after the tour moved to
`/tour/`.

`themes/meghna-hugo/layouts/alias.html` **overrides Hugo's built-in alias template**, and the reason
is specific to this site: the built-in writes the target as an absolute URL built from
`site.BaseURL`, which Hugo intermittently resolves to `http://localhost:1313/` when the theme CSS is
recompiled in the same run (see the gotcha below). `build.sh` fails the build on a localhost `href`
— correctly, since a published redirect pointing at the reader's own machine is worse than no
redirect. The override uses `.RelPermalink` instead and adds a visible fallback link for the case
where the meta-refresh does not fire.

* Alias paths are counted in Hugo's build summary (`Aliases │ 5`) and the output is a plain
  `dist/<old-path>/index.html`.
* `check-links.mjs` treats an alias page like any other page, so links pointing at the old URL keep
  passing — but prefer updating the link to the new target anyway.

<a id="scroll-reveal-shared"></a>
## Scroll reveal (shared by `/`, `/tour/` and `/studio/`)

One script drives the motion on both design-led pages:
`themes/meghna-hugo/assets/js/reveal.js`. Its contract is three hooks and nothing page-specific:

| Hook | Meaning |
| --- | --- |
| `data-reveal-scope` on a container | scan this subtree |
| `class="reveal"` | fade/slide in when scrolled into view (adds `.is-visible`) |
| `data-reveal-delay="<n>"` | stagger this one by *n* × 90 ms |
| `data-count-up` | count the number up from zero when it scrolls in |

Two partials wire it up — put both in any new page that wants it:

```go-html-template
<main class="hm-page" data-reveal-scope>
  {{ partial "reveal-bootstrap.html" . }}   {{/* inline, sets .reveal-js during parse */}}
  …
</main>
{{ partial "reveal-script.html" . }}        {{/* loads reveal.js, deferred + SRI */}}
```

> **Never hide content behind JavaScript.** The hidden start state is scoped to the `.reveal-js`
> class that `reveal-bootstrap.html` sets *synchronously during parse* (a deferred script would let
> the finished page paint and then blank it). The same snippet removes the class again after 2.5 s
> if `reveal.js` never runs, so a blocked script degrades to "no animation", never to "no content".
> Every "animate in" rule in `home.css`/`tour.css`/`studio.css` follows the same shape: `.reveal-js` hides,
> `.is-visible` reveals. The illustrations hang off the same class — their keyframes are written as
> `.is-visible .foo`, which is why revealing a container starts its art.

## Announcements

`/announcements/` carries release announcements — currently the **MetaLoom 1.0.0** page, which
describes an **unreleased** version on purpose.

* `content/english/announcements/_index.adoc` — section page (title + `subtitle`).
* `content/english/announcements/<slug>/index.adoc` — one page bundle per announcement.
* Layouts: `themes/meghna-hugo/layouts/announcements/{list,single}.html`; styles are the `.ann-*`
  block at the end of `less/includes/custom.less`.

Front matter beyond `title`/`date`/`description`:

| Key | Purpose |
| --- | --- |
| `status` | `upcoming` or `released` — selects the badge colour (`.ann-badge-upcoming` is warm/orange). |
| `status_label` | The badge text, e.g. `Not released yet`. Defaults to `Released`. |
| `version` | The version the announcement is about. |
| `image` | Social card for that announcement (`/images/og-metaloom-1-0-0.jpg` for 1.0.0). |

> **An unreleased version must read as unreleased.** The 1.0.0 page leads with an `[IMPORTANT]`
> block stating that no artifacts are published and the tree is `1.0.0-SNAPSHOT`, the badge says
> *Not released yet*, and its social card carries the same label. When the release is actually cut,
> flip `status` to `released`, update the badge label, drop the `[IMPORTANT]` block and regenerate
> the card — in one change.

## Social cards & page metadata

`partials/card.html` builds the Open Graph and Twitter/X metadata for **every** page; `head.html`
uses the same fallback chain for `<title>` and `<meta name="description">`.

* **Title** — `<page title> | MetaLoom`, except the home page, which is just `MetaLoom`.
* **Description** — page `description:` → Hugo `.Summary` → `site.Params.description`
  (config.toml), trimmed and truncated to 200 characters.
* **Image** — a page with its own `image`/`image_webp` (blog posts) shares that; everything else
  shares `/images/og-default.jpg`, a **1200×630** branded card. `twitter:card` is
  `summary_large_image`, so a square image would be letterboxed — do not point the default at
  `banner_square.webp` again.
* Also emitted: `canonical`, `og:site_name`, `og:type` (`article` for `blog`/`announcements`,
  `website` otherwise), `og:locale` (`en_US`, derived from the `en-us` locale), `og:image:alt`,
  and `article:published_time`/`article:author` on articles.

### Regenerating the social cards

The cards in `static/images/og-*.jpg` are **rendered from an HTML template with Playwright** — there
is no design source file to keep in sync, just re-render:

1. Write a 1200×630 HTML page (dark background, one of the `static/images/extra/*.jpg` light-streak
   photos, `images/logo_word_big.svg`, headline + one sentence + `metaloom.io`).
2. Serve `dist/` (e.g. `python3 -m http.server 8099 --directory dist`) so the logo and photo
   resolve, then screenshot it with the Playwright/Chromium already installed under `loom-ui/`:

   ```js
   const page = await browser.newPage({ viewport: { width: 1200, height: 630 } });
   await page.goto('file:///path/to/card.html', { waitUntil: 'networkidle' });
   await page.screenshot({ path: 'og-default.jpg', type: 'jpeg', quality: 88 });
   ```
3. Copy the result into `website/static/images/`. Keep them JPEG and around 50–60 KB.

## Theme & Layouts

`themes/meghna-hugo/` is a vendored, customized copy of the Meghna Hugo theme.

| Layout | Applies to | Notes |
| --- | --- | --- |
| `layouts/docs/single.html` | leaf docs pages | 3-col: sticky TOC sidebar (`#toc`, bootstrap-toc) + `<h1>{{.Title}}</h1>` + `{{.Content}}`. |
| `layouts/docs/list.html` | docs section pages (`_index.adoc`) | centered wide column, no sidebar. |
| `layouts/index.html` | home page | Short front door; copy from `data/en/home.yml`. See [The home page](#the-home-page). |
| `layouts/features/list.html` | `/features/` | Renders `data/en/feature.yml` as cards, `(planned)` titles become badges. |
| `layouts/tour/list.html` | `/tour/` | Bespoke scroller; see [The /tour/ page](#the-studios-page). |
| `layouts/studio/list.html` | `/studio/` | The commercial scroller; see [The /studio/ page](#the-studio-page). |
| `layouts/alias.html` | every `aliases:` entry | Redirect stub — overrides Hugo's built-in so the target is a **relative** URL; see [Aliases](#aliases-redirects). |
| `layouts/announcements/list.html` | `/announcements/` | Newest-first list of announcement cards with status badges. |
| `layouts/announcements/single.html` | one announcement | Docs-style TOC sidebar + a nav of the other announcements. |
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
| `[[Languages.en.menu.main]]` | Tour (2), Features (3), Studio (4), Announcements (5), Blog (6), Docs (6) | Top navigation entries + weights. All point at real pages — no `pre = "#"` anchors. |
| `params.logo` | `images/logo_word_big.svg` | Header logo asset. |
| `params.discordLink` | `https://discord.gg/NFdnFcSbfA` | Community link; rendered as the header's icon. Must live under `[params]` — as a root key it is invisible to templates. |
| `params.canonical_base` | `https://metaloom.io` | Base for the absolute URLs in the social metadata. Duplicates `baseURL` on purpose — see the gotcha below. Keep the two in sync. |

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
| `website/build.sh` | Theme CSS build (yarn) + `hugo` + localhost-link and broken-link checks. |
| `website/check-links.mjs` | Broken-internal-link + missing-anchor checker over `dist/`. |
| `website/content/english/_index.md` | Home-page front matter (`page_css`, description). |
| `website/data/en/home.yml` | **All copy** for the home page. |
| `website/themes/meghna-hugo/layouts/index.html` | Home-page layout. |
| `website/themes/meghna-hugo/layouts/partials/home/*.html` | Hero weave backdrop + the two door marks. |
| `website/themes/meghna-hugo/assets/css/home.css` | Styles for `/` and `/features/`. |
| `website/themes/meghna-hugo/assets/js/reveal.js` | Shared scroll-reveal + count-up. |
| `website/themes/meghna-hugo/layouts/partials/reveal-{bootstrap,script}.html` | The two lines that wire a page to `reveal.js`. |
| `website/content/english/features/_index.md` | `/features/` front matter. |
| `website/content/english/tour/_index.md` | The `/tour/` page stub (front matter only; copy lives in `data/en/tour.yml`; carries `aliases: [/studios/]`). |
| `website/data/en/tour.yml` | **All copy** for `/tour/`. |
| `website/themes/meghna-hugo/layouts/tour/list.html` | `/tour/` layout + section order. |
| `website/themes/meghna-hugo/layouts/partials/tour/art-*.html` | The illustrations on `/tour/` (one per panel). |
| `website/themes/meghna-hugo/assets/css/tour.css` | `/tour/` stylesheet (page-scoped via `page_css`). |
| `website/content/english/studio/_index.md` | The `/studio/` page stub. |
| `website/data/en/studio.yml` | **All copy** for `/studio/`, including the editions table rows. |
| `website/themes/meghna-hugo/layouts/studio/list.html` | `/studio/` layout + section order. |
| `website/themes/meghna-hugo/layouts/partials/studio/art-*.html` | The six Studio illustrations. |
| `website/themes/meghna-hugo/assets/css/studio.css` | `/studio/` stylesheet (amber, `.sd-*`). |
| `website/themes/meghna-hugo/layouts/alias.html` | Redirect stub for `aliases:` front matter (relative URL, not absolute). |
| `website/content/english/announcements/**` | Release announcements (`_index.adoc` + one bundle per release). |
| `website/themes/meghna-hugo/layouts/announcements/*.html` | Announcement list/detail layouts. |
| `website/themes/meghna-hugo/layouts/partials/card.html` | OG/Twitter metadata for every page (title, description, image chains). |
| `website/themes/meghna-hugo/layouts/partials/navigation.html` | Site header (sticky, translucent, active-section marking). |
| `website/themes/meghna-hugo/layouts/partials/footer.html` | Site-wide footer (four link columns, contact pills, Impressum link). |
| `website/content/english/docs/legal/impressum/index.adoc` | Austrian Impressum + Datenschutz (German; **has placeholders**). |
| `website/static/images/og-default.jpg`, `og-metaloom-1-0-0.jpg` | 1200×630 social cards. |
| `website/watch.sh` | Local preview server. |
| `website/content/english/docs/_index.adoc` | Docs landing (card grid, reading order, concepts). |
| `website/content/english/docs/**/index.adoc` | Individual customer doc pages (AsciiDoc). |
| `website/content/english/docs/variables.adoc-include` | Shared AsciiDoc attributes. |
| `website/content/english/blog/*/index.adoc` | Blog posts. |
| `website/data/en/*.yml` | Landing-page section copy (edit these, not partials). |
| `website/i18n/en.yaml` | UI string translations. |
| `website/static/` | Verbatim assets: `images/`, `CNAME`, `.nojekyll`. |
| `website/static/docs/examples/openapi.{json,yaml}` | Staged OpenAPI spec — downloadable and rendered by the API explorer. |
| `website/themes/meghna-hugo/layouts/partials/func/page-image.html` | Resolves a page's `image_webp`/`image` to a site-relative URL (blog teaser, hero, og:image). |
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
| Change home-page text | `website/data/en/home.yml` (the legacy `about.yml`/`service.yml`/… are no longer rendered) |
| Change the feature list | `website/data/en/feature.yml` — it drives `/features/` |
| Change the text on `/tour/` | `website/data/en/tour.yml` (not the layout) |
| Change the text on `/studio/` | `website/data/en/studio.yml` (not the layout); the commercial reasoning is in [../METALOOM_STUDIO_PLAN.md](../METALOOM_STUDIO_PLAN.md) |
| Redirect an old URL to a new one | `aliases:` in the target page's front matter — the stub comes from `layouts/alias.html` |
| Add scroll-reveal to a new page | `data-reveal-scope` + `.reveal` + the two `reveal-*` partials |
| Add/redraw an illustration on `/tour/` | `website/themes/meghna-hugo/layouts/partials/tour/art-<name>.html` + styles in `assets/css/tour.css` |
| Add/redraw an illustration on `/studio/` | `website/themes/meghna-hugo/layouts/partials/studio/art-<name>.html` + styles in `assets/css/studio.css` |
| Add a release announcement | New bundle under `website/content/english/announcements/<slug>/index.adoc` with `status`/`status_label` |
| Change what a shared link looks like (social card) | `website/themes/meghna-hugo/layouts/partials/card.html`; the images are `website/static/images/og-*.jpg` |
| Give one page its own stylesheet | Front matter `page_css: css/<name>.css` + the asset under `themes/meghna-hugo/assets/css/` |
| Find out why a link 404s | `cd website && node check-links.mjs` |
| Change top navigation | `[[Languages.en.menu.main]]` blocks in `config.toml` |
| Change UI labels ("Read more", menu names, footer links) | `website/i18n/en.yaml` |
| Record which model a node uses and its license | `website/content/english/docs/legal/model-licenses/index.adoc` |
| State how the code was produced (AI disclosure) | `website/content/english/docs/legal/ai-disclosure/index.adoc` |
| Change the header / top navigation | `[[Languages.en.menu.main]]` in `config.toml` for the entries; `partials/navigation.html` + `.navigation` rules in `custom.less` for the look |
| Change the footer | `website/themes/meghna-hugo/layouts/partials/footer.html`, labels in `i18n/en.yaml`, contact pills in `[[params.social]]` |
| Fill in the Impressum | `website/content/english/docs/legal/impressum/index.adoc` — the `[…]` placeholders and the comment block at the top |
| Change docs page layout / TOC | `website/themes/meghna-hugo/layouts/docs/single.html` (+ `list.html`) |
| Add global CSS/JS plugin | `[[params.plugins.css]]` / `[[params.plugins.js]]` in `config.toml` |
| Change site colors/styles | `website/themes/meghna-hugo/less/` (rebuild via `build.sh`) |
| Add images to a page | Put them in the same page-bundle folder as the `.adoc` |
| Change how a blog teaser/hero image is resolved | `website/themes/meghna-hugo/layouts/partials/func/page-image.html` |
| Fix "build fails with localhost links" | Escape the URL in the `.adoc` (`\http://localhost:8092`) — see [the localhost-link check](#the-localhost-link-check) |
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
* **`index.adoc` in a folder makes it a *leaf* bundle — its subfolders stop being pages.** This
  silently swallowed `docs/deployment/helm/`: the page existed in the source tree, three links
  pointed at it, and Hugo published nothing because `docs/deployment/index.adoc` made the
  directory a leaf bundle. A section that has (or may gain) child pages must use `_index.adoc`,
  which also switches it from `docs/single.html` to `docs/list.html` (no TOC sidebar).
* **A static path in front matter needs a leading slash.** `image: images/team/js.jpg` is resolved
  against the *page bundle* first and then appended to the page's own URL, which produced
  `/author/jotschi/images/team/js.jpg`. Write `/images/team/js.jpg`.
* **Page-scoped CSS exists.** `page_css: css/<name>.css` in front matter makes `head.html` emit one
  extra stylesheet for that page only. Use it for bespoke pages instead of growing `custom.less`,
  which is loaded site-wide.
* **A menu entry with `pre = "#"` is an anchor on the home page, not a page.** `features` pointed at
  `#feature` and `blog` at `#blog`; when those sections left the home page every menu link on the
  site turned into a dead anchor. The link checker catches it now (it validates fragments), but the
  rule is simpler: menu entries should point at real pages.
* **Absolute URLs come from `site.Params.canonical_base`, not `site.BaseURL`.** Hugo intermittently
  resolves `site.BaseURL` to `http://localhost:1313/` for a handful of pages when the theme CSS is
  rebuilt in the same run. That used to show up only in metadata; once `partials/card.html` emitted
  a `<link rel="canonical">`, it started failing the localhost check outright. `card.html` therefore
  builds canonical/`og:url`/`og:image` from the param, which cannot be defaulted. Do not "simplify"
  it back to `.Permalink` or `absURL`.
* **A running `hugo server` writes into `dist/`.** `watch.sh` (or any `hugo server`) publishes to the
  same `dist/` and injects `<script src="/livereload.js…">` into the pages it renders. If a preview
  server is running while you build, `build.sh` fails on that script tag — correctly, since
  publishing it would 404 on the live site. Stop the preview server before a release build, or build
  into a scratch directory (`hugo -d /tmp/distcheck && node check-links.mjs /tmp/distcheck`).
* **Site-relative over absolute in templates.** Anything the browser fetches (`src`, `href`,
  stylesheet/plugin paths) must come from `.RelPermalink` / `relURL`, not `.Permalink` / `absURL`.
  Besides the double-slash trap above, Hugo occasionally resolves `site.BaseURL` to its default
  `http://localhost:1313/` for a subset of pages in a build (see the open item below) — a relative
  URL is immune to that.
* **Hugo sometimes emits `http://localhost:1313` absolute URLs.** Reproducible on Hugo 0.158 *and*
  0.164: when the theme CSS is recompiled in the same run (i.e. every `./build.sh`), 5–15 of the ~90
  pages render with `site.BaseURL` = `http://localhost:1313/` even though `config.toml` sets
  `baseURL = "https://metaloom.io"`; a second `hugo` run without a CSS change comes out clean. It is
  not a `dist/` staleness issue (it reproduces after `rm -rf dist`) and not concurrency alone
  (`GOMAXPROCS=1` still shows it). Since the fix above made every *link* relative, what is left
  affected is absolute-URL **metadata** — `og:url`, `og:image`, `twitter:image:src` and the RSS
  `<link>`/`<guid>` elements. The `build.sh` check deliberately covers link/resource attributes only,
  so it does not fail the build on this; see the open item in [Progress](#progress-assessment).
* **`build.sh` runs `yarn install`**, which rewrites `themes/meghna-hugo/yarn.lock` (and can pin
  packages to whatever registry the build machine uses). Don't commit that churn along with a
  content change.
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
5. Internal links are checked automatically — `build.sh` runs `check-links.mjs` and fails on a
   broken target or a missing `#anchor`. Run `node check-links.mjs` on its own for a quick pass.
6. For a visual check of `/tour/` or `/studio/` (or any page) without a browser, serve `dist/` and drive the
   Playwright/Chromium already installed under `loom-ui/`:

   ```bash
   python3 -m http.server 8099 --directory dist &
   # navigate to http://localhost:8099/tour/, scroll the page so the IntersectionObserver
   # reveals every section, then screenshot at 1440px and 420px wide
   ```

   Scroll before shooting: everything on that page starts hidden until it is revealed.
7. For the two design-led scrollers, check **horizontal overflow at 420 px** as well —
   `document.documentElement.scrollWidth` must equal the viewport width minus the scrollbar gutter,
   and no `main` descendant may be clipped by `.st-page`/`.sd-page`'s `overflow-x: hidden`. That
   clipping is silent: the page still scrolls correctly, the content is just cut off. It is what a
   `white-space: nowrap` run inside a `1fr` grid track causes — see
   [The /studio/ page](#the-studio-page).
8. If a page was moved, confirm the alias still resolves: `dist/<old-path>/index.html` must exist
   and carry a **relative** refresh target (`dist/studios/index.html` → `/tour/`).
9. Dry-run publish: from the sibling `metaloom-website` repo, `./pull.sh` then inspect `docs/`
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
- [x] `/studios/` renamed to `/tour/` (content dir, `data/en/tour.yml`, `layouts/tour/`,
      `partials/tour/art-*`, `assets/css/tour.css`, `assets/images/scenery/`), every in-site link
      updated, and `aliases: [/studios/]` + a `layouts/alias.html` override keeping the old URL alive
- [x] `/studio/` added — the commercial edition scroller (amber `.sd-*`, seven illustrations, an
      accessible editions table, mailto CTA); reasoning and open decisions in
      [../METALOOM_STUDIO_PLAN.md](../METALOOM_STUDIO_PLAN.md)
- [ ] `/studio/` carries no pricing — the "announced with 1.0.0" lines in `data/en/studio.yml` have
      to be replaced once decision D-5 in the Studio plan is made
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
- [x] Fixed the same defect at its source in `examples/cortex-python/daemon.py` — it posted the wire
      state (`COMPLETED`) to `/assets/:uuid/node-results`, which `asset_node_result_state_check`
      rejects, losing the ledger row while the json-comp still landed. It now maps through
      `ledger_state()` and stamps `PRODUCER_VERSION` on both writes; `post_json_comp` gained the
      `variant`/`producerVersion` parameters. The enum is documented on `docs/cortex/examples/` and in
      the example README
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
- [x] Blog teaser/hero/social images resolve through `partials/func/page-image.html` — site-relative,
      no double slash, banner fallback. Fixes the broken overview cards, whose `src` was
      `{{ .Permalink }}/{{ .Params.Image_webp }}`
- [x] `build.sh` fails the build when `dist/` contains a link or resource attribute pointing at
      localhost; the docs that mentioned a local address in prose or a table now escape it
      (`\http://localhost:8092`) so Asciidoctor stops auto-linking it
- [x] **Broken-link check** (`website/check-links.mjs`, wired into `build.sh`) — resolves every
      internal `href`/`src`/`srcset`/`action`/`poster`/`data-*-url` against the build output and
      verifies `#anchor` fragments; fails the build with a per-page report
- [x] The 12 live 404s it found are fixed: `docs/deployment/` became a branch bundle so its
      **Helm Charts** child is actually built (3 links from the docs landing, 1 from
      `loom/helm-chart/`, 1 from the Kubernetes playbook), the GraphQL API page's sibling links
      got their `../`, `getting-started` gained explicit `#loom-ui`/`#loom-app` anchors, and the
      author page's portrait path was absolutised
- [x] **Home page rebuilt** as a short front door (`layouts/index.html` + `data/en/home.yml` +
      `assets/css/home.css`): woven-thread hero with a pre-release status pill, the *Not released
      yet* card, "Two ways in" routing visual vs technical readers, four what-it-is tiles that pair
      a plain sentence with monospace facts, a stack strip, three latest posts and a one-command
      CTA. Roughly 20 % shorter than the page it replaced, and the long feature list and blog grid
      it used to carry now live on their own pages
- [x] **Pre-release status is unmissable and honest** — hero status pill, the *Not released yet*
      card with a monospace facts list (version in tree, published artifacts, demo container),
      three working links, and a badge in the footer. The placeholder "Notify me" field was
      removed rather than left to imply a mailing list exists
- [x] **Site header reworked** — sticky translucent bar with a blur, an `.is-scrolled` solid
      state, current-section marking (teal underline / left border on mobile, `aria-current`),
      capitalised labels, a Discord icon, and a hamburger that folds into an X over a solid mobile
      panel
- [x] **The tour hero given motion** (the page was `/studios/` then) — three blended gradient stripes travelling along the
      photograph's band axis plus a slow teal swell, and an explicit bottom fade so the colour
      bands dissolve into the page instead of ending on an edge. `transform`/`opacity` only
- [x] **Reading pages unified** — one typographic system across docs, announcements and blog
      (Quattrocento Sans headings, Anaheim prose, monospace values, readable table cells, code
      chips), a shared `.page-head`, and a sticky footer so short pages stop leaving the footer
      mid-viewport. The docs sidebar was deliberately left as it was apart from its fonts
- [x] **Blog reworked** — overview cards (image, date, title, summary, whole-card link) driven by
      `content/english/blog/_index.md`, and a post layout with a sticky TOC, byline, hero image
      with credit and a *More posts* list
- [x] **Site footer rebuilt** — four link columns (brand + status badge, Explore, Documentation,
      Project), copyright line and contact pills driven by `[[params.social]]`; the placeholder
      Twitter icon pointing at `#` is gone, and bootstrap-toc is scoped to `.docs-main-content` so
      the footer headings stop appearing in the docs TOC
- [x] **Impressum & Datenschutz page** (`docs/legal/impressum/`) — § 5 ECG / § 25 MedienG
      disclosure in German, plus what the static site processes (GitHub Pages logs, Google Fonts,
      email); linked from the footer of every page and from the legal card grid
- [x] **`/features/`** — the full `feature.yml` list on its own page, `(planned)` titles rendered as
      badges, linked from the navigation and from the home page
- [x] **Scroll reveal extracted to `assets/js/reveal.js`** with a page-agnostic contract
      (`data-reveal-scope` / `.reveal` / `data-reveal-delay` / `data-count-up`) and two wiring
      partials, shared by `/`, the tour and `/studio/`
- [x] **Absolute URLs no longer depend on `site.BaseURL`** — `card.html` builds canonical/`og:url`/
      `og:image` from `site.Params.canonical_base`, which closes the long-standing
      `http://localhost:1313` metadata flake
- [x] **The product tour** (built as `/studios/`, now `/tour/` — paths below are the *old* ones)
      — a design-led, image-led scroller for media studios, archives and creators:
      full-bleed hero, the "lost filename" problem, an animated pipeline figure, six capability
      panels each with its own illustration (speech, faces, scenes, translation, fingerprints,
      chat agent), a numbers strip, the on-premise/open-source section, three audience cards and a
      one-command CTA. Copy is now `data/en/tour.yml`, art `partials/tour/art-*.html`,
      page-scoped `assets/css/tour.css` + the shared `assets/js/reveal.js`, photography processed to webp
      by Hugo. Degrades to the finished page with JavaScript off and to a static page under
      `prefers-reduced-motion`
- [x] **`/announcements/`** — section, list/detail layouts, `.ann-*` styles, top-menu entry, and
      the **MetaLoom 1.0.0** announcement: what is in the release (pipeline engine, nodes, agent,
      APIs, front ends, storage/search/ACL, deployment) plus an *Important points* table (license,
      no shipped weights, the two non-commercial components, database, hardware, per-worker node
      availability, API stability) — clearly marked **not released yet** in the badge, the lead
      block and the social card
- [x] **Social/summary metadata rebuilt** (`partials/card.html`): `summary_large_image` cards,
      canonical URL, `og:site_name`/`og:type`/`og:locale`/`og:image:alt`, article timestamps, a
      real title (`<page> | MetaLoom`) and a description chain (page → summary → site), plus two
      generated 1200×630 cards (`og-default.jpg`, `og-metaloom-1-0-0.jpg`). The site-wide
      description fallback is no longer the stale "MetaLoom 2021"
- [x] `CLI` and `GraphQL` lost their `(planned)` marker in `data/en/feature.yml` — both ship; the
      CLI blurb now describes the native binary / JAR client
- [ ] Flip the 1.0.0 announcement from `status: upcoming` to `released` when the release is cut —
      badge label, the `[IMPORTANT]` lead block and the social card all have to change together
- [ ] The broken-link check does not fetch **external** links; a dead `https://` link on the site
      is still invisible. An opt-in network pass (or a scheduled job) would close that
- [ ] **Fill in the Impressum placeholders** (address, direct contact besides email) — the page is
      not legally complete until then, and the register/UID/trade rows have to be revisited if the
      project ever becomes commercial
- [ ] Self-host the two web fonts instead of loading them from Google's CDN — that transfer is the
      only reason the Impressum needs a Google Fonts paragraph
- [ ] The **RSS** `<link>`/`<guid>` elements still come from `site.BaseURL` (Hugo's internal
      template), so the `localhost:1313` flake can still reach them. The page metadata is fixed —
      see `canonical_base` — but the feed would need a custom RSS template to be immune
- [ ] The legacy Meghna landing partials and their data files (`about`, `service`, `skill`,
      `funfacts`, `pricing`, `testimonial`, `portfolio`, `contact`, `map`, `banner`, `cta`, `blog`)
      are no longer rendered by any layout. Delete them, or park them in `content-off/`-style
      fashion, so nobody edits copy that cannot appear
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

_GIT HEAD: `29cadb66ae5b37c9c6a4c6f18ef5f39807a0cec7` (branch `master`)_
_Generated: 2026-07-28 (UTC) — `/studios/` → `/tour/` rename, new `/studio/` commercial page,
alias mechanism_
