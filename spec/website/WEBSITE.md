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
├── interaction/           # Loom & Cortex interaction / online-vs-offline model (Loom owns the DAG)
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
│   ├── features/          # assets, users, groups, roles, tags, pipelines
│   ├── chat/              # Chat & AI Agent — agentic loop, Sessions, Skills (w/ examples), Memory, coding sandbox (+ deployment)
│   ├── artifacts/ · maven-artifacts/ · containers/ · helm-chart/  # deploy/coordinates
│   └── examples/          # snippets from the /examples module
├── cortex/                # ── Cortex subsystem (now a daemon that serves nodes) ──
│   ├── _index.adoc        # engine overview
│   ├── features/ · configuration/ · monitoring/ · artifacts/    # node pages live under top-level nodes/ now
│   ├── maven-artifacts/ · containers/ · examples/   # examples cover Java node, Java daemon, Python worker
└── (legacy stubs)         # rest/, test/, configuration/ — old placeholder pages, see Gotchas
```

> The **coding sandbox** deployment (podman/k8s backends, RBAC, `LOOM_AGENT_SANDBOX_*`) now lives
> inside `loom/chat/` (§ Coding Sandbox), not a separate `loom/agent-sandbox/` page. The per-node
> reference moved from `cortex/nodes/` to the top-level `nodes/` section. The landing feature list is
> data-driven from `data/en/feature.yml` (includes the Chat & AI Agent and Cortex Processing Nodes
> items).

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
3. A running server also serves the same document live at `/api/v1/openapi.json`; the embedded
   Swagger UI (`themes/meghna-hugo/static/plugins/swagger/swagger.js`) points at a `url` that must
   resolve to a copy of that JSON. When staging the spec into the site, refresh that file and keep
   the `swagger.js` `url` in sync (it currently references a `docs/examples/openapi.json` path).
4. `LoomOpenAPITest` guards generation; run `mvn -pl loom/services/rest test` after endpoint changes.

> ⚠️ The checked-in `loom/doc/src/main/generated/openapi.json` can go stale relative to the code.
> Regenerate it in the same change as any REST endpoint edit, and re-stage it for the website.

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
- [x] Loom docs: REST API, Java client, authentication, configuration, features, artifacts,
      maven-artifacts, containers, helm-chart, examples
- [x] Cortex docs: nodes, features, configuration, monitoring, artifacts, maven-artifacts,
      containers, examples
- [x] Shared conceptual docs: interaction model, pipeline mechanism
- [x] Container Images page (`docs/deployment/`) with the full image + port inventory
- [x] Chat & AI Agent docs (`docs/loom/chat/`) — agentic loop, Sessions, Skills, Memory, coding sandbox
- [x] Agentic Coding Sandbox deployment (`docs/loom/agent-sandbox/`) — podman/k8s backends, RBAC, config
- [x] Cortex docs updated to the daemon-that-serves-nodes model (Loom owns the DAG); ports use `8092`
- [x] Cortex examples cover a custom node (Java), a custom daemon (Java) and a custom worker (Python)
- [x] REST API page extended with pipelines run/versions, processors, chats+stream, sessions, skills,
      memory, and the `json-comps`/`node-results` persistence endpoints
- [x] OpenAPI regeneration documented (see "Updating the embedded OpenAPI spec")
- [x] Fixed a generation artifact in `loom/maven-artifacts/index.adoc` (stray `*** Add File:` blob
      duplicating containers/helm content with the old `metaloom/loom:latest` image name)
- [x] Swagger UI plugin wired for the Loom REST API
- [x] Blog section with initial posts
- [x] GitHub Pages publish flow via sibling `metaloom-website` repo (`pull.sh`, CNAME)
- [ ] Remove/consolidate legacy stub pages (`docs/rest/`, `docs/test/`, top-level
      `docs/configuration/`) into the maintained Loom/Cortex pages
- [ ] Refresh & re-stage the embedded `openapi.json` (regenerate via `ExampleGenerator`) and fix the
      `swagger.js` `url` (still points at `localhost:1313/docs/examples/openapi.json`)
- [ ] Fill remaining thin pages (e.g. `helm-chart`) with full content
- [ ] Automate build+publish (currently manual `build.sh` + `pull.sh` + push)
- [ ] Keep customer docs in sync with product specs under `spec/` (ongoing)

---

_GIT HEAD: `6d454bc0e90fc6849f33b191fff84608367d66eb` (branch `master`)_
_Generated: 2026-07-25 (UTC)_
