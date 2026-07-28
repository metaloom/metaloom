# MetaLoom Studio — Commercial Offering Plan

> **Status: proposal, nothing decided.** This file exists to hold the *options* for
> commercialising MetaLoom in one place, with the trade-offs written down, so the decision can be
> made once and then referenced. Where a recommendation is given it is a recommendation, not a
> commitment. The public page at `/studio/` already renders one particular set of these options —
> §8 says exactly which, and what to change on the site if a different one is chosen.
>
> Cross-references: [METALOOM.md](METALOOM.md) (module layout) ·
> [website/WEBSITE.md](website/WEBSITE.md) (the site that carries the pitch) ·
> [guidelines/CODING.md](guidelines/CODING.md) · [SPEC_RULES.md](SPEC_RULES.md)

---

## 1. What "Studio" Is

**MetaLoom Studio** is the proposed commercial edition of MetaLoom. The platform — Loom server,
Cortex engine, every processing node, REST/GraphQL/CLI, the UI — stays Apache 2.0 and self-hosted.
Studio is the layer a *company* buys: identity integration, storage at scale, commercially cleared
model licenses, supported builds, and a support agreement.

The naming collision is already resolved on the website:

| Path | What it is |
| --- | --- |
| `/tour/` | The visual product tour for media studios, archives and creators. **Used to be `/studios/`** — renamed 2026-07-28, with a Hugo alias so old links redirect. |
| `/studio/` | **This** — the commercial edition. |

```
                    ┌──────────────────────────────────────────────┐
                    │  MetaLoom (Apache 2.0, unchanged)            │
                    │  Loom · Cortex · 19 node kinds · UI · APIs   │
                    └──────────────────────────────────────────────┘
                                        ▲
                        additive only ─ │ ─ never subtractive
                                        │
        ┌───────────────────────────────┴──────────────────────────────┐
        │  MetaLoom Studio                                             │
        │  identity · storage tiering · cleared models · LTS · support │
        └──────────────────────────────────────────────────────────────┘
```

---

## 2. The Constraints That Shape The Model

Anything chosen here has to survive these. They are facts about the project, not preferences.

| # | Constraint | Consequence |
| --- | --- | --- |
| C1 | The platform is already **Apache 2.0 and published**. | Nothing already shipped can be pulled back. Any gate applies to *new* code only. |
| C2 | Two default models are **non-commercial**: the InspireFace packs (facedetect / facedescription) and Ideogram 4.0 (planned imagegen). See `website/content/english/docs/legal/model-licenses/`. | Commercial users have a real, immediate, unsolved problem. That is a product, not a nuisance. |
| C3 | The pitch everywhere is **"on hardware you control"**. | A hosted/metered model contradicts the one thing the site says on every page. |
| C4 | Air-gapped installs are a stated capability. | Entitlement cannot require a call home. |
| C5 | The project is **one person**. | Anything sold has to be deliverable by one person. Support volume is the binding constraint, not engineering. |
| C6 | 1.0.0 is **not released** (`1.0.0-SNAPSHOT`, no published artifacts). | There is nothing to sell yet. Everything here lands with or after 1.0.0. |
| C7 | Cortex nodes are **pluggable by design** (Python workers, custom node docs). | Any gate on "which nodes may run" is trivially routed around, and the docs teach how. |

---

## 3. The Options

Each option: what it is, why it works, why it might not, verdict. Verdicts are
**Adopt** / **Consider** / **Defer** / **Reject**.

### A. Support & SLA subscription — *Adopt*

Sell a support agreement: written response times, a named engineer, migration help, roadmap input.
No code changes at all.

* **For:** Zero engineering cost. Legal to sell against Apache 2.0. The thing procurement actually
  needs is a counterparty, and this is that. Works from day one.
* **Against:** Revenue is capped by C5 — one person can carry a handful of accounts. Does not scale
  without hiring, and an SLA you cannot meet is worse than none.
* **Note:** Price the *response time*, not the hours. Start with a tier that is honestly deliverable
  solo (next business day) and only sell 1-hour P1 when there is someone on the other shift.

### B. Identity & governance add-ons (SSO, SCIM, audit trail) — *Adopt*

OIDC/SAML single sign-on, SCIM provisioning/de-provisioning, group→role mapping, an immutable audit
log, retention policies.

* **For:** The canonical open-core split, and the one that frustrates nobody: a solo user never
  wants SAML, and an org cannot deploy without it. `loom/services/auth` already has
  `auth-keycloak` / `auth-okta` / `auth-auth0` stubs, so the shape exists.
* **Against:** The stubs are in the open-source tree today. Anything *already working* there stays
  (C1) — the Studio module must be new code (full SCIM, audit ledger, SAML), not a re-gating of
  what is there.
* **Watch:** "SSO tax" gets criticised publicly. The defence is that basic auth, JWT, API keys and
  RBAC are all free — only the *directory integration* is paid.

### C. S3 / object storage as a Studio feature — *Consider, with a split* ⭐ open decision D-2

The user's explicit question. S3 is listed as **(planned)** in `website/data/en/feature.yml` — it
does not exist yet, so gating it does not violate C1.

* **For:** Anyone with a petabyte has a budget. Storage is where large installs feel pain, so
  willingness to pay is highest exactly where the feature matters. It is new code, so it is clean.
* **Against:** S3 is *table stakes* for anyone above a few dozen TB, and it is also the single
  easiest thing for a community contributor to implement — if it is withheld, someone writes it,
  and the split leaks. Withholding basic object storage also reads as artificial in a way that
  withholding SAML does not.
* **Recommendation — split the feature, do not gate the protocol:**
  * **Open source:** a plain S3-compatible storage backend. Put a bucket in, read and write from it.
  * **Studio:** the *lifecycle* layer on top — hot/warm/cold tiering rules, multi-site replication,
    lifecycle policies, restore-from-cold orchestration, per-library placement.
  * This gates the part that only an organisation runs, is much harder to reimplement casually, and
    keeps "MetaLoom cannot use my storage" from ever being true.
* **Related in-flight work:** `features/pipeline-nodes/NODE_S3SOURCE_PLAN.md` (currently an empty
  placeholder). An S3 *source node* — reading assets out of a bucket — is a different thing from the
  storage *backend*, and under this recommendation it belongs in the open tree with the other
  source nodes. Settle D-2 before that plan is written, so the node is not designed against a
  storage layer whose licence is still undecided.

### D. Limiting pipeline nodes / assets / pipelines — *Reject* ⭐ open decision D-3

The user's other explicit question, and the instinct to be suspicious of it is right.

* **Against, decisively:**
  * It contradicts the "no metered processing" promise the site now makes, and that promise is
    worth more than the revenue — it is the difference from every cloud MAM.
  * It punishes exactly the behaviour the marketing celebrates ("19 node kinds", "point it at a
    folder"). The user hits the wall while *succeeding*, which reads as a trap, not an upsell.
  * It is trivially circumvented — C7 means anyone can run a second Cortex or write a Python worker.
    A limit that only inconveniences honest users is the worst kind.
  * It creates support load in the shape of "why did my pipeline stop", which is the one cost
    constraint C5 cannot absorb.
* **The rule to adopt instead:** sell along the **organisational** axis (identity, compliance,
  licensing, support), never the **capacity** axis (nodes, assets, minutes, seats-as-throughput).

### E. Commercially cleared model bundle + indemnity — *Adopt (strongest single idea)*

Ship a curated, version-pinned set of models whose licenses permit commercial use, with written
provenance, and name it in the agreement.

* **For:** C2 is a real blocker that a customer *cannot solve alone* — reading model licenses is
  specialist work, and getting it wrong is a legal exposure, not a technical one. It is defensible
  (it is diligence and paperwork, not code, so nobody forks it), it renews naturally as models
  change, and it is uniquely credible for this project because the legal inventory already exists on
  the site.
* **Against:** It carries actual liability. Indemnity must be scoped by a lawyer, and the diligence
  has to be redone on every model bump. Do not offer indemnity before that review exists.

### F. Signed builds, SBOM, LTS line — *Adopt*

Signed container images, SBOM attestation, and a `1.0.x` LTS branch that receives security and
correctness fixes without feature churn.

* **For:** Cheap to produce (mostly CI), high perceived value in regulated procurement, and the LTS
  branch is work that has to happen anyway once anyone runs this in production.
* **Against:** The LTS branch is a *maintenance commitment* — the one line item here that gets more
  expensive every year. Cap the support window explicitly (e.g. 18 months) before selling it.

### G. Custom nodes, connectors & integration work — *Adopt*

Build the house-specific node (legacy MAM, delivery spec, odd codec) and maintain it across upgrades.

* **For:** Highest per-hour value, and it is how the roadmap learns what real pipelines need.
* **Against:** Pure services revenue — it does not compound and it consumes exactly the capacity C5
  limits. Treat it as a paid discovery channel, not the business.

### H. OEM / white-label licensing — *Consider*

Someone embeds MetaLoom in their own product and wants terms, a support path, and their branding.

* **For:** Large deals, few accounts — the best fit for a one-person company. Apache 2.0 permits the
  embedding already, so what is sold is support, indemnity and the right to co-brand.
* **Against:** Apache 2.0 means they can also just… do it. The only leverage is the Studio-only
  components and the support relationship, which makes H dependent on B/E/F existing first.

### I. Hosted / managed MetaLoom (SaaS) — *Reject for now*

* Contradicts C3 head-on, requires 24/7 operations (C5), and puts customer media on infrastructure
  that then has to be secured and insured. Revisit only if the positioning changes, and if it does,
  the whole `/tour/` and `/studio/` narrative changes with it.

### J. Dual licensing (AGPL + commercial) for *new* subsystems — *Consider*

Future subsystems could ship AGPL-3.0 with a commercial exception, rather than closed.

* **For:** Source stays readable and auditable — closer to the project's character than a closed
  module. Sells to exactly the companies that cannot ship AGPL.
* **Against:** Only applies to code not yet written (C1). Two licenses in one repo is a real
  cognitive and CI cost, and AGPL scares off some evaluators before they read why.

### K. Node / skill marketplace — *Defer*

A place to publish and sell nodes and agent skills, with a revenue share.

* Needs a community that does not exist yet at 1.0.0. Revisit when there are third-party nodes in
  the wild. The `/api/v1/skills` library is the seed if it ever happens.

### L. Training & certification — *Defer*

Real revenue in the broadcast market, but it needs a stable 1.0 and written curriculum first.

---

## 4. Recommended Shape (if a decision has to be made today)

**Studio = B + C(split) + E + F + A**, sold as one annual agreement, with **G** as separate
engagement work and **H** as a bespoke contract. **D is off the table permanently.**

The line to hold, in one sentence: *Studio sells what an organisation needs, never what a user
needs.*

---

## 5. Open Decisions

| ID | Decision | Options | Recommendation |
| --- | --- | --- | --- |
| D-1 | Is Studio a separate repo, a separate Maven module tree, or a build flavour? | (a) `metaloom-studio` private repo consuming public artifacts · (b) `studio/` tree in this repo with a non-open license header · (c) feature flags in the open tree | **(a)** — keeps the open tree unambiguously Apache 2.0 and makes "what is open" answerable by looking at one repository. |
| D-2 | Is S3 storage a Studio feature? | (a) fully Studio · (b) **backend open, tiering/replication Studio** · (c) fully open | **(b)** — see §3.C. |
| D-3 | Do we ever limit node/pipeline/asset counts? | (a) yes · (b) no | **(b) no.** See §3.D. Write it into the public promise so it cannot drift. |
| D-4 | How is entitlement enforced? | (a) honour system + contract · (b) signed offline license file · (c) license server | **(a) or (b)**; never (c) — C4. Start with (a): a customer with a signed contract is not the threat model, and (b) is weeks of work protecting revenue that does not exist yet. |
| D-5 | Pricing shape? | per-seat · per-worker/GPU · per-TB managed · flat site license · support tier | **Flat annual site license by organisation size band.** Per-TB and per-worker are capacity metering by another name (D-3). Per-seat is hard to police on a self-hosted install. |
| D-6 | Is there a free trial of Studio? | (a) time-limited entitlement file · (b) evaluation contract · (c) none | **(b)** while sales are one-to-one; (a) only after D-4(b) exists. |
| D-7 | Does Studio get its own version line? | (a) tracks platform versions · (b) independent | **(a)** — one codebase, one version, as the `/studio/` page states. |
| D-8 | Indemnity scope for the model bundle (§3.E)? | needs a lawyer | **Blocked on legal review.** Do not put the word "indemnity" in a contract before it. It is already on the marketing page as a bullet — see §8. |

---

## 6. Engineering Implications

What has to exist before each adopted option can be sold. Nothing here is built.

| Option | Work | Touches |
| --- | --- | --- |
| B — SSO/SCIM/audit | SAML support, full SCIM 2.0 endpoints, append-only audit ledger + retention job | `loom/services/auth/*`, `loom/services/rest`, `loom/db/flyway` (new `V*.sql`), `loom/db/api` + `jooq` DAOs |
| C — storage tiering | Storage backend abstraction with a lifecycle/placement layer above it | `loom/services/fs`, asset binary handling, new DAO fields for placement/tier |
| E — model bundle | A pinned manifest of model coordinates + licenses, a verification job, and the diligence document | New; relates to `docs/legal/model-licenses/` and the per-node `*Options` classes named there |
| F — signed builds / LTS | Image signing + SBOM in CI, a `1.0.x` maintenance branch, backport policy | `loom/containers`, `cortex/container`, CI |
| A / G / H | None (contractual) | — |

> **The `/features/` page and `feature.yml` are the single source of feature truth on the site.**
> If a capability becomes Studio-only, it still belongs in `website/data/en/feature.yml` — marked as
> such — not silently dropped.

### Environment variables (proposed, none implemented)

Listed so the naming is decided once. All are inert today.

| Variable | Default | Purpose |
| --- | --- | --- |
| `LOOM_STUDIO_LICENSE_FILE` | *(unset)* | Path to the offline entitlement file (D-4 option b). Absent ⇒ community behaviour. |
| `LOOM_STUDIO_ORG` | *(unset)* | Organisation name the entitlement was issued to; surfaced in the UI footer and support bundles. |
| `LOOM_AUTH_SAML_*` | *(unset)* | SAML IdP configuration (option B). Mirrors the existing `LOOM_AUTH_*` OIDC keys. |
| `LOOM_SCIM_ENABLED` | `false` | Enables the SCIM 2.0 provisioning endpoints (option B). |
| `LOOM_AUDIT_ENABLED` | `false` | Enables the append-only audit ledger (option B). |
| `LOOM_STORAGE_TIERING_ENABLED` | `false` | Enables lifecycle/tiering policies over the S3 backend (option C). |

---

## 7. Risks & Anti-Goals

* **Never move a shipped feature into Studio.** It is the one action that would cost more trust than
  any feature could earn back, and `/studio/` now states it publicly with a date.
* **Never meter.** See D-3. The moment a counter exists, the "no per-minute bill" pitch is dead.
* **Do not promise an SLA that one person cannot meet** (C5). Under-promise the response tier.
* **Do not ship "indemnity" before legal review** (D-8).
* **Do not let the open build rot.** Open core fails when the free edition quietly stops being
  usable. The community edition must stay the thing the tour page describes.
* **Watch the Impressum.** `website/content/english/docs/legal/impressum/index.adoc` currently
  assumes "private project, no Firmenbuch, no UID, no trade licence" and still contains `[…]`
  placeholders. **The moment anything is actually sold, those rows and the address/phone
  placeholders have to be corrected** — it is an Austrian legal duty, not a content task.

---

## 8. What The Website Currently Claims

`/studio/` renders **§4's recommended shape**. If a decision in §5 changes, these are the exact
places to edit — in the same change as the decision.

| Claim on the page | Source | Depends on |
| --- | --- | --- |
| Six capability panels (identity, storage, licensing, operations, support, fit) | `website/data/en/studio.yml` → `panels:` | B, C, E, F, A, G |
| "S3-compatible object storage, tiering, multi-site" as Studio-only | `studio.yml` → `panels[storage]`, `editions.rows` | **D-2** — if D-2(c) is chosen, delete the panel and the row |
| "Nothing is taken away" / "No metered processing" / "runs air-gapped" | `studio.yml` → `open_core.rules` | **D-3**, C4 — these are load-bearing promises; do not soften them |
| "+ indemnity" in the editions table and licensing chips | `studio.yml` → `panels[licensing].chips`, `editions.rows` | **D-8** — remove until legal review is done |
| Example response times (1 h / 4 h / next day) | `layouts/partials/studio/art-support.html` | Illustrative only; the partial says so. Real figures live in the contract, never on the page. |
| "no price list", "pricing announced with 1.0.0" | `studio.yml` → `hero.status`, `early_access` | D-5, C6 |
| `hello@metaloom.io` mailto as the only contact | `studio.yml` → `early_access.primary` | Deliberately not a form — there is no list to subscribe anyone to |

The page layout, art and styling are specified in
[website/WEBSITE.md § The /studio/ page](website/WEBSITE.md).

---

## 9. Key Files Reference

| File | Purpose |
| --- | --- |
| `spec/METALOOM_STUDIO_PLAN.md` | **This file** — the options and the open decisions |
| `website/data/en/studio.yml` | All copy on `/studio/` |
| `website/content/english/studio/_index.md` | `/studio/` front matter (`page_css: css/studio.css`) |
| `website/themes/meghna-hugo/layouts/studio/list.html` | `/studio/` layout and section order |
| `website/themes/meghna-hugo/layouts/partials/studio/art-*.html` | The six illustrations |
| `website/themes/meghna-hugo/assets/css/studio.css` | `/studio/` stylesheet (amber, `.sd-*`) |
| `website/data/en/tour.yml` + `layouts/tour/` + `assets/css/tour.css` | The **other** page — the product tour, formerly `/studios/` |
| `website/data/en/feature.yml` | The single source of feature truth for the site |
| `website/content/english/docs/legal/model-licenses/index.adoc` | The license inventory option E is built on |
| `website/content/english/docs/legal/impressum/index.adoc` | Austrian disclosure — **must be corrected before selling** |
| `loom/services/auth/` | Where option B would land (`auth-keycloak`/`okta`/`auth0` stubs) |
| `loom/services/fs/` | Where option C would land |

---

## 10. Where do I find …?

| I want to … | Look at |
| --- | --- |
| Change what `/studio/` says | `website/data/en/studio.yml` (not the layout) |
| Add or redraw a Studio illustration | `layouts/partials/studio/art-<name>.html` + `.sd-*` styles in `assets/css/studio.css` |
| Change the editions table | `studio.yml` → `editions.rows` |
| Understand the page's build/motion/CSS rules | [website/WEBSITE.md](website/WEBSITE.md) |
| Find out why `/studios/` redirects | `website/content/english/tour/_index.md` → `aliases:` + `layouts/alias.html` |
| See which models block commercial use | `website/content/english/docs/legal/model-licenses/` |
| Decide whether a capability is Studio-only | §3 and §5 of this file — then update §8 and `feature.yml` |

---

## 11. Test Setup

There is no runtime to test — this is a planning document plus one static page. Verification is:

1. Build the site (`cd website && ./build.sh`, or `hugo` alone if the theme CSS is unchanged).
   `build.sh` fails on localhost links and on broken internal links.
   ⚠️ The system `hugo` is 0.131 and **cannot** build this site — see
   [website/WEBSITE.md](website/WEBSITE.md) for the version requirement (≥ 0.158 extended).
2. `node check-links.mjs` for a link-only pass.
3. Visual check of `/studio/` at 1440 px and 420 px by serving `dist/` and driving the
   Playwright/Chromium already installed under `loom-ui/`. **Scroll the page before shooting** —
   every section starts hidden until the IntersectionObserver reveals it.
4. Confirm `/studios/` still serves the redirect page to `/tour/` (`dist/studios/index.html`).
5. Confirm no horizontal overflow at 420 px (`document.documentElement.scrollWidth` must equal the
   viewport width minus the scrollbar gutter).

---

## 12. Progress Assessment

- [x] `/studios/` renamed to `/tour/` with a redirect alias, so `/studio/` is unambiguous
- [x] `/studio/` page built: hero, open-core ledger, six capability panels, numbers, editions
      table, audiences, early-access CTA — scroll-revealed, amber-accented, mobile-checked
- [x] Commercial options catalogued with trade-offs and verdicts (§3)
- [x] The two questions that prompted this written up explicitly: S3 (D-2) and node limits (D-3)
- [x] Open decisions D-1 … D-8 recorded with recommendations
- [x] Website ↔ decision mapping so the page and the plan cannot silently diverge (§8)
- [ ] **D-1 … D-8 are undecided.** Nothing below can start until they are.
- [ ] Legal review of the indemnity scope (D-8) — blocks §3.E being sold as written
- [ ] Impressum corrected for commercial operation (§7) — blocks selling anything at all
- [ ] Pricing decided (D-5) and the "pricing with 1.0.0" line on the page replaced
- [ ] No Studio code exists: SSO/SCIM/audit (B), storage tiering (C), model bundle (E),
      signed builds + LTS (F) are all unstarted
- [ ] `/studio/` is not linked from the home page's "two ways in" — deliberate for now (the home
      page routes visual vs. technical); revisit when there is something to buy

---

_Git HEAD revision: `29cadb66ae5b37c9c6a4c6f18ef5f39807a0cec7`_
_Last updated: 2026-07-28 (initial version — created with the `/studio/` page and the
`/studios/` → `/tour/` rename)_
