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

* **Hugo** (extended build recommended — theme CSS is compiled from LESS in the theme's own
  yarn build, but Hugo's asset pipeline / SCSS security exec is enabled in `config.toml`).
* **Node + Yarn** — used by the theme to compile CSS (`themes/meghna-hugo` has its own
  `package.json`).
* **asciidoctor** — required because docs/blog are AsciiDoc. `config.toml` explicitly allows the
  `asciidoctor` external binary under `[security.exec] allow`. Without it, `.adoc` pages render
  empty or Hugo errors.

### Commands

| Command | What it does |
| --- | --- |
| `./build.sh` | Full build: `cd themes/meghna-hugo && yarn install && yarn build` (compiles theme CSS), then `hugo` at the project root → writes `dist/`. |
| `./watch.sh` | Runs `build.sh` then `hugo server -b http://localhost:1313/` for live local preview. |
| `hugo` | Site build only (assumes theme CSS already built). Output → `dist/`. |

`build.sh` uses `set -o errexit -o nounset` — a missing `asciidoctor`/`yarn`/`hugo` fails the
whole script. `dist/` and `docs/` are git-ignored in this repo (see `website/.gitignore`), so a
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
│   └── docs/examples/openapi.json   # staged OpenAPI doc rendered by the embedded Swagger UI
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
│   └── examples/          # snippets from the /examples module
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

### Updating the embedded OpenAPI spec (Swagger UI)

The REST API page (`docs/loom/rest-api/`) documents the endpoint surface by hand, but the site also
embeds a **live Swagger UI** (the `plugins/swagger/*` assets, wired in `config.toml`). The OpenAPI
document it renders is **generated from the Loom server's endpoint registry** — it is not written by
hand and must be regenerated whenever endpoints change:

1. The generator lives in the `loom/doc` module:
   `io.metaloom.loom.doc.impl.OpenAPIGenerator` calls
   `io.metaloom.loom.rest.openapi.LoomOpenAPI#generateJson()` and writes
   `loom/doc/src/main/generated/openapi.json`.
2. `io.metaloom.loom.doc.ExampleGenerator#main` runs all doc generators (OpenAPI + Loom config +
   REST model). Run it after adding/removing/renaming REST endpoints or changing DTOs:

   ```bash
   mvn -q -pl loom/doc -am exec:java \
     -Dexec.mainClass=io.metaloom.loom.doc.ExampleGenerator
   ```
   (Working directory must be `loom/doc/` — the generator writes to the relative
   `src/main/generated/openapi.json`.)
3. Stage the generated file into the site as `website/static/docs/examples/openapi.json` (copy it —
   nothing does this automatically). That is the path the embedded Swagger UI loads.
4. A running server also serves the same document live at `/api/v1/openapi.json`.

**Swagger UI wiring (`themes/meghna-hugo/static/plugins/swagger/swagger.js`)**

* The plugin JS is loaded on **every** page (`[[params.plugins.js]]` in `config.toml`), so the
  script must bail out when `#swagger-ui` is absent — otherwise SwaggerUIBundle renders into `null`
  and throws *React error #200* site-wide.
* The `url` must stay **site-relative** (`/docs/examples/openapi.json`). An absolute
  `http://localhost:1313/...` URL makes the published site fetch the *visitor's* machine, which
  fails CORS and triggers the browser's Local Network Access prompt
  ("metaloom.io wants to access other apps and services on this device").
* The mount point is a raw-HTML block `<div id="swagger-ui"></div>` in
  `docs/loom/rest-api/index.adoc`; a per-page `data-openapi-url` attribute overrides the default URL.
`LoomOpenAPITest` guards generation; run `mvn -pl loom/services/rest test` after endpoint changes.

> ⚠️ The checked-in `loom/doc/src/main/generated/openapi.json` can go stale relative to the code.
> Regenerate it in the same change as any REST endpoint edit, and re-stage it for the website.

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
  API keys, …), so every screen has real content.

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
| `chat.png` | `/` — Chat & AI Agent (landing)
| `skills.png` | Skills nav
| `memory.png` | Memory nav
| `assets.png` | Assets nav
| `asset-detail.png` | Assets → first asset card
| `library.png` | Library nav
| `tags.png` | Tags nav
| `face-detection.png` | Detection nav (defaults to the Faces tab)
| `pipeline-editor.png` | Pipelines nav
| `pipeline-versions.png` | Pipelines → version badge (history popover open)
| `cortex.png` | Cortex nav
| `users.png` | Admin → Users
| `acl-roles.png` | Admin → Permissions (ACL matrix)
| `api-keys.png` | Admin → API Keys
|===

> Some views are unseeded in a bare demo (Skills, Agent Memory and per-library contents). The script
> captures whatever the demo actually contains — do not fabricate data.

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
* Rich landing/section layout (card grids, note boxes) is done with **raw HTML passthrough
  blocks** `++++ ... ++++` embedding Bootstrap markup + theme CSS classes (`docs-card`, `note`,
  `row`, `col-*`). See `docs/_index.adoc` for the canonical pattern.
* Shared attributes live in `docs/variables.adoc-include` (`:icons: font`,
  `:source-highlighter: prettify`, `:toc:`). The `.adoc-include` extension keeps Hugo from
  rendering it as a standalone page.
* Some pages open with a level-0 title (`= Title`) in the body in addition to front-matter
  `title:` (e.g. `interaction/`); prefer the front-matter title and level-2 (`==`) sections for
  new pages to match the docs layout, which already emits the `<h1>` from `title`.

### Landing page (data-driven)

The home page (`themes/meghna-hugo/layouts/index.html`) is assembled from **partials**
(`banner`, `about`, `feature`, `cta`, `service`, `skill`, `team`, `funfacts`, `pricing`,
`testimonial`, `blog`, `contact`, `map`). Each partial reads its copy from a matching
`data/en/<name>.yml` file — **edit the YAML, not the partial**, to change landing-page text.
Several partials (pricing, portfolio, testimonial, contact, map) are wired in the layout but
their menu entries are commented out in `config.toml`.

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
| `website/themes/meghna-hugo/layouts/docs/*.html` | Docs page/section templates. |
| `website/themes/meghna-hugo/layouts/index.html` | Home-page partial pipeline. |
| `website/pom.xml` | Maven module registration (no build logic). |
| `metaloom-website/pull.sh` (sibling repo) | Copies `dist` → staging `docs/` for GitHub Pages. |

## Where do I find …?

| I want to … | Look at |
| --- | --- |
| Add/edit a customer doc page | `website/content/english/docs/<section>/index.adoc` |
| Add a new docs section | New folder under `docs/` with `_index.adoc` (section) + child `index.adoc` pages; link it from `docs/_index.adoc` |
| Change landing-page text | `website/data/en/<section>.yml` |
| Change top navigation | `[[Languages.en.menu.main]]` blocks in `config.toml` |
| Change UI labels ("Read more", menu names) | `website/i18n/en.yaml` |
| Change docs page layout / TOC | `website/themes/meghna-hugo/layouts/docs/single.html` (+ `list.html`) |
| Add global CSS/JS plugin | `[[params.plugins.css]]` / `[[params.plugins.js]]` in `config.toml` |
| Change site colors/styles | `website/themes/meghna-hugo/less/` (rebuild via `build.sh`) |
| Add images to a page | Put them in the same page-bundle folder as the `.adoc` |
| Change the published domain | `website/static/CNAME` (and staging `docs/CNAME`) + `baseURL` |
| Fix "asciidoc renders empty" | Ensure `asciidoctor` installed and allowed in `[security.exec]` |
| Understand how the site is published | [Publishing Flow](#publishing-flow) / `metaloom-website/pull.sh` |

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
- [x] Blog section with initial posts
- [x] GitHub Pages publish flow via sibling `metaloom-website` repo (`pull.sh`, CNAME)
- [ ] Remove/consolidate legacy stub pages (`docs/rest/`, `docs/test/`, top-level
      `docs/configuration/`) into the maintained Loom/Cortex pages
- [ ] Re-run `ExampleGenerator` so the staged `openapi.json` matches current endpoints (the staged
      copy is only as fresh as `loom/doc/src/main/generated/openapi.json`)
- [ ] Only load the ~1 MB Swagger UI bundle on the REST API page instead of globally
- [ ] Fill remaining thin pages (e.g. `helm-chart`) with full content
- [ ] Automate build+publish (currently manual `build.sh` + `pull.sh` + push)
- [ ] Keep customer docs in sync with product specs under `spec/` (ongoing)

---

_GIT HEAD: `6d454bc0e90fc6849f33b191fff84608367d66eb` (branch `master`)_
_Generated: 2026-07-25 (UTC)_
