# MetaLoom // Frontend Style Guide

> The design system for MetaLoom's two frontend surfaces: the **Loom UI** (the product,
> `loom-ui/` — React + MUI) and the **website** (`website/` — Hugo). It documents the system *as
> built*: every colour, radius and rule below is taken from the token sources listed in §2. When
> this file and the code disagree, the code wins — fix this file in the same change.
>
> This guide covers the *look*: colours, typography, shape, menus, component styling and the CSS
> rules that keep both surfaces coherent. Shell behaviour, routing and screen structure live in
> [LOOM_UI.md](../loom/ui/LOOM_UI.md); website content, build and gates live in
> [WEBSITE.md](../website/WEBSITE.md) (its "Design system" section is the website-side authority
> that §5 here summarises).

---

## 1. Design principles

One identity, two implementations. Both surfaces read as the same product because they agree on
five decisions:

1. **Dark-first.** The website is dark only (`--ml-bg: #0b0e13`). The Loom UI defaults to dark
   (`#0d0f11`) and offers a light mode as a token swap, never as a redesign — every component is
   written against tokens, so the light theme is the same UI with a different palette.
2. **One accent.** Teal `#57cbcc` is the brand colour on both surfaces — primary actions, active
   nav, focus rings, selection. Colour carries meaning: on the website teal marks *what is open
   source* and the Studio pages use amber `#e2a86e` for the commercial offer; that is the only
   sanctioned accent deviation (see [WEBSITE.md](../website/WEBSITE.md) § Design system).
3. **Depth from hairlines, not shadows.** On a near-black page drop shadows are invisible.
   Surfaces separate through 1px translucent white borders (`rgba(255,255,255,.07–.16)`), slightly
   lighter fills, and — on website cards — a very shallow gradient. The only box-shadows in the
   system are the menu popover shadow and the teal button glow on hover.
4. **Modern, quiet chrome.** Rounded corners (6–24px scale), no uppercase buttons
   (`textTransform: "none"`), thin 6px scrollbars, generous line height (1.5–1.6), sentence-case
   labels. Uppercase + letterspacing is reserved for *labels of structure*: table heads and
   sidebar section dividers.
5. **Motion is decoration.** Transitions are 150ms ease on borders/backgrounds; every website
   page-scoped stylesheet ends with a `prefers-reduced-motion` block; nothing encodes information
   in movement alone.

---

## 2. Token sources — where colours are allowed to live

| Surface | File | Mechanism |
|---|---|---|
| Loom UI | `loom-ui/src/theme/index.ts` | `darkTokens` / `lightTokens` objects + `buildTheme(mode)` (MUI theme). Consumers `import { tokens } from "../../theme"` — a Proxy that always resolves the active mode. **There is no `src/theme/tokens.ts`.** |
| Website | `website/themes/meghna-hugo/less/includes/custom.less` | CSS custom properties on `:root` (`--ml-*`), compiled into the global `main.css`, so they exist on every page |

**Rule: components do not own colours.** A feature component references `tokens.*` (Loom UI) or
`var(--ml-*)` (website); a raw hex value in a feature file is a defect. The palette changes in one
place per surface or it stops being a palette.

Theme mode plumbing (Loom UI): `ThemeContext` holds `mode` (`dark` | `light`, default **dark**),
persists it under the `localStorage` key `loom-ui-theme`, and calls `setActiveTokens(mode)` +
`buildTheme(mode)`. See [LOOM_UI.md](../loom/ui/LOOM_UI.md) §6.

---

## 3. Colour

### 3.1 Loom UI palette

Backgrounds are a five-step elevation ramp — each step is one surface "closer" to the user.
Use the step that matches the role, not the one that looks right in isolation.

| Token | Dark | Light | Role |
|---|---|---|---|
| `bg.base` | `#0d0f11` | `#f5f6f8` | The page itself |
| `bg.surface` | `#141719` | `#ffffff` | Sidebar, inputs, table heads |
| `bg.panel` | `#1a1d22` | `#f0f1f3` | `Paper`, panels |
| `bg.elevated` | `#20252c` | `#ffffff` | Cards |
| `bg.overlay` | `#353b43` | `#e4e6ea` | Menus, tooltips, neutral chips |
| `bg.hover` / `bg.active` | teal at 6% / 10% alpha | teal at 6% / 10% alpha | Interactive hover/pressed washes |

| Token | Dark | Light | Role |
|---|---|---|---|
| `border.subtle` | `rgba(255,255,255,.07)` | `rgba(0,0,0,.08)` | Default hairline: cards, dividers, table rows |
| `border.default` | `rgba(255,255,255,.10)` | `rgba(0,0,0,.12)` | Inputs, menus, card hover |
| `border.strong` | `rgba(255,255,255,.16)` | `rgba(0,0,0,.20)` | Input hover, scrollbar thumb |
| `text.primary` | `#e8e9eb` | `#1a1d22` | Body and headings |
| `text.secondary` | `#737f8a` | `#5f6b77` | Supporting copy, icons, table heads |
| `text.tertiary` | `#4f5a63` | `#8a95a0` | Captions, de-emphasised values |
| `text.disabled` | `#353b43` | `#bdc3ca` | Disabled controls |

| Token | Dark | Light | Role |
|---|---|---|---|
| `primary.main` | `#57cbcc` | `#349495` | The brand teal — buttons, active nav, focus |
| `primary.light` / `primary.dark` | `#88dcdd` / `#3ba8a9` | `#57cbcc` / `#267070` | Gradient ends, selected-text tint |
| `primary.glow` | teal @ 25% | teal @ 25% | Button hover glow |
| `primary.subtle` | teal @ 10% | teal @ 8% | Selected nav item, text selection |
| `accent.blue` | `#2ea8ff` | `#1a8fe0` | Secondary accent, info |
| `accent.green` | `#34d58a` | `#28a96e` | Success |
| `accent.amber` | `#f5a623` | `#d48e1a` | Warning / degraded |
| `accent.red` | `#f0546e` | `#d43f58` | Error / failed |
| `accent.teal` | `#00c9b1` | `#00a895` | Data-viz alternate |
| `accent.violet` | `#9d7bea` | `#7a55c8` | Data-viz alternate |

Note the light palette keeps the *same teal family* but darkened for contrast on white
(`#349495` main); the dark palette's teal becomes light mode's `primary.light`.

### 3.2 Status colours — the four tones

Operational state is never a free colour choice. `StatusChip`
(`loom-ui/src/components/StatusChip.tsx`) fixes a four-tone vocabulary, because the first thing
an operator reads is working / degraded / broken / not-applicable, and that reading must be
identical on every screen:

| Tone | Colour | Meaning |
|---|---|---|
| `green` | `accent.green` on 15% wash | Healthy, succeeded, online |
| `amber` | `accent.amber` on 15% wash | Degraded, pending, warning |
| `red` | `accent.red` on 15% wash | Failed, offline, error |
| `neutral` | `text.tertiary` on `bg.overlay` | Not applicable, unknown, idle |

Use `StatusChip` (or `toneStyles(tone)`) for any state badge; do not invent a fifth tone or a
per-screen green.

### 3.3 Website palette

Single dark palette, defined once on `:root` in `custom.less`:

| Group | Tokens |
|---|---|
| Surfaces | `--ml-bg` `#0b0e13` · `--ml-bg-alt` `#10151c` (recessed: code, zebra, rails) · `--ml-surface` / `--ml-surface-hi` (2.8% / 5% white) · `--ml-card` (a 160deg 6%→1.5% white gradient — cards get depth without shadows) |
| Lines | `--ml-line` (9% white) · `--ml-line-hi` (16% white) |
| Text | `--ml-fg` `#eef2f6` · `--ml-fg-dim` `#aab6c2` · `--ml-muted` `#7d8894` |
| Accent | `--ml-accent` `#57cbcc` · `--ml-accent-bright` `#7efff4` · `--ml-accent-line` (55% teal) · `--ml-accent-wash` (10% teal) |
| Warm (Studio only) | `--ml-warm` `#e2a86e` · `--ml-warm-soft` `#f0c799` |
| Shape | `--ml-radius` 14px · `--ml-radius-sm` 10px · `--ml-lift` `translateY(-3px)` |

`home.css` defines **no colours** — it aliases the tokens. `/tour/` (`.st-*`) and `/studio/`
(`.sd-*`) keep page-scoped stylesheets on purpose and are the only place the accent may differ;
inside `studio.css` teal survives as `--sd-teal` and marks exactly one thing: what is open source.

---

## 4. Typography

| Aspect | Loom UI | Website |
|---|---|---|
| Prose | `Inter` (Google Fonts, weights 300–700), fallback Roboto | `Anaheim` (`--ml-sans`) |
| Display / headings | Inter 600–700, negative letterspacing (−0.01 to −0.02em) | `Quattrocento Sans` (`--ml-display`) |
| Monospace | browser default | `JetBrains Mono` (`--ml-mono`) — values, code chips |
| Body line height | 1.6 (`body1`), 1.5 (`body2`) | theme prose defaults |
| Buttons | weight 600, `textTransform: "none"` | theme buttons |
| Table heads | 0.75rem, uppercase, `letterSpacing: 0.06em`, `text.secondary` | monospace values in tables |
| Secondary copy | `body2`/`subtitle2` are pre-tinted `text.secondary`; `caption` is `text.tertiary` | `--ml-fg-dim` / `--ml-muted` |

Website constraint: **no CJK text anywhere** — the site ships no CJK webfont, so a Japanese line
renders as tofu.

---

## 5. Shape, depth and micro-interaction

### 5.1 Radius scale (Loom UI, `tokens.radius`)

| Token | Value | Used for |
|---|---|---|
| `sm` | 6px | Menu items, tooltips |
| `md` | 10px | Buttons, inputs, nav items, menus, alerts — the default (`shape.borderRadius: 10`) |
| `lg` | 16px | Cards |
| `xl` | 24px | Hero surfaces |
| `full` | 9999px | Chips, pills, avatars |

The website's `--ml-radius` (14px) / `--ml-radius-sm` (10px) sit in the same family.

### 5.2 The card object

Identical on both surfaces by design: a slightly elevated fill, a `border.subtle` hairline, a
larger radius, and a hover that *strengthens the border and fill* rather than adding a shadow —
Loom UI cards transition `border-color`/`background-color` over 150ms; website cards
(`.hm-feature`, `.docs-card`, `.note`, `.ann-entry`, `.blog-card`) add the `--ml-lift` translate.

### 5.3 Buttons (Loom UI)

* **Contained** = the teal statement: `linear-gradient(135deg, primary.main → primary.dark)`,
  no resting shadow, and on hover a soft glow `0 0 18px primary.glow`. One per view, for the
  primary action.
* **Outlined** = everything else: `border.default` border; hover swaps to a teal border +
  `primary.subtle` wash.

### 5.4 Scrollbars and selection

6px scrollbars, transparent track, `border.strong` thumb (3px radius), `text.tertiary` on hover.
Text selection is `primary.subtle` behind `text.primary`. Both are set globally in
`MuiCssBaseline` — never per component. Website side: prefer `scrollbar-color`; Chromium ignores
`::-webkit-scrollbar-*` once `scrollbar-width` is not `auto`.

---

## 6. Menus and navigation

### 6.1 The Loom UI sidebar

`loom-ui/src/layout/Sidebar.tsx` — the primary menu of the product. Structure and behaviour are
specified in [LOOM_UI.md](../loom/ui/LOOM_UI.md) §4.3; the visual rules:

* **Geometry.** Fixed 220px; collapses to a 56px icon rail (`ChevronLeft`/`ChevronRight`
  toggle). On the rail, sub-group headers are dropped and their items render flat — an icon rail
  has no second level.
* **Background** is `bg.surface` (one step above the page), separated by a `border.subtle` line.
* **Sections** (AI / CONTENT / MANAGEMENT) are labelled by small uppercase dividers in
  `text.secondary` — the same treatment as table heads, because both label structure.
* **Items** are `ListItemButton`s styled centrally in `buildTheme`: `radius.md`, icon in
  `text.secondary` (`minWidth: 36`), hover = `bg.hover` (the 6% teal wash).
* **The selected item is the one place three teal signals stack**: `primary.subtle` background,
  a `2px solid primary.main` left border, label in `primary.light`, icon in `primary.main`.
  Exactly one item is selected; nothing else in the sidebar is teal.
* **Sub-groups** (ACL) collapse; closed unless one of their routes is active — a deep link never
  lands on a page whose entry is hidden.
* **Badges** (upload count) use MUI `Badge`, weight 700, 0.65rem.
* Navigation goes through `useLayout().requestNavigation`, never `navigate` directly — the
  sidebar is where unsaved work is defended.

### 6.2 Dropdown / context menus (Loom UI)

Styled once in `buildTheme` — a feature never restyles a menu:

| Part | Rule |
|---|---|
| `MuiMenu` paper | `bg.overlay` fill, `border.default` hairline, `radius.md`, shadow `0 8px 32px rgba(0,0,0,0.5)` — the one true drop shadow, earned by floating over everything |
| `MuiMenuItem` | `radius.sm`, `margin: 2px 6px` (inset from the paper edge), 0.875rem, hover = `bg.hover` |
| `MuiTooltip` | `bg.overlay` + `border.default`, `radius.sm`, 0.75rem — a mini-menu, same family |

The avatar menu in the sidebar header (Profile / Logout) is a standard `Menu` with these styles
plus its own testids (`sidebar-avatar-*`) — see [LOOM_UI.md](../loom/ui/LOOM_UI.md) §4.3.

### 6.3 Tabs (Loom UI)

Compact: `minHeight: 38`, 0.8125rem, sentence case. Resting tabs are `text.secondary` weight
500; the selected tab is `text.primary` weight 600 over a 2px teal indicator (1px radius).
Colour marks the *indicator*, weight marks the *label* — the label itself never turns teal.

### 6.4 Website navigation

* **Top nav** compacts on scroll and hides on downward scroll below 992px (shows again after
  26px of upward travel — asymmetric so a late image or font swap does not flicker it).
* **Breadcrumb** renders below the top nav on docs pages, with `BreadcrumbList` JSON-LD.
* **Docs sidebar rail** sits on `--ml-bg-alt` (the recessed band); the active entry uses the
  accent, mirroring the product sidebar's "one teal item" rule.
* Heading anchor offsets use `scroll-margin-top: 96px`, not padding.

---

## 7. Component vocabulary (Loom UI)

All central overrides live in `buildTheme` (`loom-ui/src/theme/index.ts`); this is the intended
look per component. Extend the theme there rather than re-styling per screen.

| Component | Style |
|---|---|
| `Paper` | `bg.panel`, `border.subtle`, `backgroundImage: none` (kills MUI's elevation gradient) |
| `Card` | `bg.elevated`, `border.subtle`, `radius.lg`; hover strengthens border + fill (§5.2) |
| `Chip` | `radius.full`, weight 500. Status chips: `StatusChip` only (§3.2) |
| `TextField` | `radius.md`, `bg.surface` fill; border ramps `border.default` → `border.strong` (hover) → `primary.main` (focus) |
| `Table` | Heads per §4; body rows 0.85rem over `border.subtle` rules; no zebra |
| `Alert` / `LinearProgress` | `radius.md` / 4px |
| `Drawer` | `bg.surface` + `border.subtle`, no background image |
| `EmptyState` | Shared component (`components/EmptyState.tsx`): haloed icon + headline + description + optional CTA. Bound to *the collection being empty*, never to a filtered result — see [LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.5 |
| Search fields | `TextField size="small"` with a `SearchOutlined` startAdornment and a `<feature>-search` testid — the testid is part of the rule ([LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.5.1) |

---

## 8. CSS rules

### 8.1 Loom UI

1. **Tokens only.** Style with `sx` referencing `tokens.*`; import from `../../theme`
   (adjust depth). No hex/rgba literals in feature code — if a colour is missing, add a token.
2. **Component-wide looks belong in `buildTheme`.** If every instance of a component should look
   a certain way, that is a `components.Mui*.styleOverrides` entry, not a copy-pasted `sx`.
3. **Both modes, always.** Anything styled through tokens works in light mode for free; anything
   hardcoded breaks it. Check new UI in both modes (toggle persists as `loom-ui-theme`).
4. **No new styling systems.** The stack is MUI + Emotion (`sx`); do not introduce CSS modules,
   Tailwind or styled-components alongside it.
5. **Alpha washes for interaction states** (`bg.hover`, `primary.subtle`), or `alpha()` from
   `@mui/material/styles` when a token needs a one-off opacity — never a second opaque colour.

### 8.2 Website

1. **Global styles go in `custom.less`** (compiled by `yarn build` into `main.css`). Page-scoped
   stylesheets exist only for `/tour/` and `/studio/` and are hand-written CSS — do not fold them
   into `main.css`, and do not add new page-scoped sheets without the same justification.
2. **`html, body { background-color: var(--ml-bg) }` is load-bearing.** The theme's `style.css`
   still sets `#353b43`; `custom.less` wins by import order. A grey page means a
   `background-color` is beating the token block.
3. **Figures are inline SVG** using the shared `.ml-*` vocabulary (`ml-figure`, `ml-arch-svg`,
   `ml-box-container`, `ml-box-part`, `ml-edge`, `ml-chip`, `ml-step`, `ml-flow`, `ml-deny`, …) —
   never ASCII art. Prefix `<marker>` ids per page (`ml-dk-*`, `ml-tr-*`, …): marker ids are
   document-global and two figures reusing `ml-arrow` collide. Give each SVG `<title>` + `<desc>`
   wired via `aria-labelledby`.
4. **Never hide content behind JavaScript.** Scroll-reveal hidden states are scoped to
   `.reveal-js` (set synchronously, auto-cleared after 2.5s) so a blocked script degrades to
   "no animation", never "no content".
5. **Every animation respects `prefers-reduced-motion`**, and an animated figure must not change
   height (fixed caption boxes; `scrollbar-gutter: stable`).

---

## 9. Do / Don't

| Do | Don't |
|---|---|
| `color: tokens.accent.red` | `color: "#f0546e"` in a feature file |
| One contained (gradient) button per view | Several teal buttons competing |
| `StatusChip` with one of the four tones | A bespoke green badge for "this screen's" success |
| Separate surfaces with `border.subtle` + a lighter `bg.*` step | `boxShadow` on cards/panels |
| Sentence case labels, weight for emphasis | Uppercase buttons, bold-as-colour |
| Extend `buildTheme` for a component-wide look | Copy the same `sx` across five screens |
| Reuse `EmptyState`, shared search-field shape, `ListControls` | New one-off variants of shared components |
| Website: tokens in `custom.less`, teal = open source, amber = Studio | New accent colours or a colour redefined outside the `:root` block |

---

## 10. Key Classes Reference

| Class / file | Location | Purpose |
|---|---|---|
| `darkTokens` / `lightTokens` / `tokens` / `buildTheme` / `setActiveTokens` | `loom-ui/src/theme/index.ts` | The entire Loom UI design system: token sets, reactive proxy, MUI theme + component overrides |
| `ThemeContext` | `loom-ui/src/context/ThemeContext.tsx` | Mode state (default dark), `localStorage` `loom-ui-theme`, wires `setActiveTokens` |
| `Sidebar` | `loom-ui/src/layout/Sidebar.tsx` | The product menu: sections, ACL sub-group, avatar menu, collapse rail |
| `GlobalSearchField` | `loom-ui/src/layout/GlobalSearchField.tsx` | Sidebar search row (own row below the header strip) |
| `StatusChip` / `toneStyles` | `loom-ui/src/components/StatusChip.tsx` | The four-tone status vocabulary |
| `EmptyState` | `loom-ui/src/components/EmptyState.tsx` | Shared empty-collection surface |
| `ListControls` | `loom-ui/src/components/ListControls.tsx` | Shared sort/filter controls for list views |
| `custom.less` | `website/themes/meghna-hugo/less/includes/custom.less` | Website `--ml-*` tokens, card object, `.ml-*` SVG vocabulary, reveal styles |
| `home.css`, `tour.css`, `studio.css`, `404.css`, `product.css` | `website/themes/meghna-hugo/assets/css/` | Page-scoped stylesheets (colour-free except tour/studio accents) |
| `reveal.js` | `website/themes/meghna-hugo/assets/js/reveal.js` | Scroll-reveal contract (`data-reveal-scope`, `.reveal`, `data-reveal-delay`) |

## 11. Where do I find ...?

| Question | Answer |
|---|---|
| A Loom UI colour / radius | `tokens.*` in `loom-ui/src/theme/index.ts` — both mode tables in §3.1 |
| How a MUI component should look everywhere | `components.Mui*` overrides in `buildTheme` |
| A website colour / font / radius | `:root` block at the top of `custom.less` (§3.3) |
| The status colour for failed/degraded/ok | §3.2 — `StatusChip` tones |
| Sidebar/menu styling rules | §6; behaviour in [LOOM_UI.md](../loom/ui/LOOM_UI.md) §4.3 |
| The website card / heading / reveal patterns | [WEBSITE.md](../website/WEBSITE.md) § Design system |
| SVG diagram classes for docs pages | §8.2 item 3; full list in [WEBSITE.md](../website/WEBSITE.md) |
| Fonts | Loom UI: Inter via Google Fonts in `loom-ui/index.html`; website: Anaheim / Quattrocento Sans / JetBrains Mono via `--ml-*` type tokens |

## 12. Conventions and gotchas

* **`tokens` is a Proxy over the active mode** — destructuring it at module load
  (`const { bg } = tokens`) freezes the group object of the then-active mode. Read through it
  (`tokens.bg.hover`) at render time.
* **`buildTheme`'s component overrides intentionally read `tokens` (the proxy), not `t`** — but
  the theme is rebuilt per mode anyway; keep new overrides consistent with their neighbours.
* **`backgroundImage: "none"` on Paper/Card/Drawer is deliberate** — MUI dark mode paints an
  elevation gradient that fights the flat hairline system. Keep it when adding surface overrides.
* **`StatusChip` wash backgrounds are hardcoded dark-palette rgba values** (e.g.
  `rgba(52,213,138,0.15)`) rather than derived from the active mode's accents — a known wrinkle;
  in light mode the washes stay tuned to the dark accents. If you fix it, derive with `alpha()`
  from `tokens.accent.*` and update this note.
* **The sidebar's placeholder must stay distinct from in-page filter boxes** — several Playwright
  specs locate filters with `getByPlaceholder(/search/i)`; the global field is on every route.
* **Website: exactly two `'` per line inside `data-nodeviz` attributes** — the JSON lives in a
  single-quoted HTML attribute; an apostrophe truncates it and the diagram silently blanks.
* **Do not reintroduce a light theme on the website** — it is dark-only by design; the Loom UI is
  the surface with a mode switch.
* Customer docs styling rules (no ASCII art, escaped localhost URLs, no class names in prose) are
  in [CODING.md](../guidelines/CODING.md) § Docs and [WEBSITE.md](../website/WEBSITE.md).

## 13. Test setup

The design system has no unit tests of its own; it is exercised through the surfaces:

* **Loom UI**: `cd loom-ui && ./node_modules/.bin/vitest run` (unit) and
  `./node_modules/.bin/playwright test` (105 e2e specs) — invoke the binaries directly, `npx`
  hangs in this repo. Styling regressions surface through testid-based specs (selected sidebar
  item, `StatusChip` testids, `<feature>-search` fields).
* **Screenshots for docs**: `loom-ui/scripts/capture-ui-screenshots.mjs` — dark theme,
  `reducedMotion`, per [WEBSITE.md](../website/WEBSITE.md) capture conventions.
* **Website**: `cd website && ./build.sh` — compiles `less/main.less`, then runs the link,
  screenshot and search-index gates. A page rendering grey (§8.2 item 2) or a blanked node
  diagram (§12) are the two visual failures the gates do *not* catch — check them in the browser.

## 14. Progress Assessment

- [x] Loom UI token system (dark + light) with MUI theme builder
- [x] Theme mode toggle, persisted (`loom-ui-theme`), default dark
- [x] Central component overrides (buttons, cards, menus, tabs, tables, inputs, scrollbars)
- [x] Four-tone status vocabulary (`StatusChip`)
- [x] Sidebar menu style: sections, one-teal-selected rule, collapse rail, avatar menu
- [x] Website token block (`--ml-*`) and shared card object across all page families
- [x] Website motion rules (`prefers-reduced-motion`, reveal contract, fixed-height figures)
- [ ] `StatusChip` washes derived from active-mode accents instead of hardcoded dark rgba (§12)
- [ ] Audit feature files for stray hex/rgba literals outside the theme (`grep -rn "#[0-9a-f]\{6\}\|rgba(" loom-ui/src/features/ --include=*.tsx`)
- [ ] Self-host the two website web fonts (also tracked in [WEBSITE.md](../website/WEBSITE.md))

---

_Git HEAD revision: `daefc256`_
_Last updated: 2026-08-20 (initial version — extracted from loom-ui/src/theme/index.ts, custom.less and WEBSITE.md § Design system)_
