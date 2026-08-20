# Finding assets

Loom's catalogue is searched through `find_assets`. It takes the whole question at once —
what the asset contains, who created it, when, and where it lives — and tells you which
filters it actually applied.

**Pass the words the user used.** Names are resolved on the server: `creator: "pete"`,
`collection: "Project XYZ"`, `when: "yesterday"`. Do not look uuids up first, and never
invent one — you have not seen them, and a wrong uuid returns a confident empty result.

## The workflow

1. Read the request and fill in `find_assets` in one call. Most questions are one call.
2. Read the `(...)` summary in the answer. It lists what was applied — check it matches
   what was asked before you report anything.
3. If the tool reports an unresolved or ambiguous name, **ask the user**. Do not retry with
   a guess and do not drop the filter.

## Worked example

> "Find me assets that were uploaded by pete today or yesterday for project xyz"

```json
{ "creator": "pete", "when": "today or yesterday", "space": "project xyz", "sort": "NEWEST" }
```

No `text`. That is correct — the question has no search term, only filters, and
`find_assets` accepts filters alone. Adding `text: "assets"` would search for the *word*
"assets" and find almost nothing.

## What goes in which field

| The user says | Field |
|---|---|
| words that should appear in or about the file | `text` |
| uploaded by / shot by / added by *someone* | `creator` |
| in project / for the *X* project | `space` |
| in the *X* collection | `collection` |
| in the *X* library | `library` |
| tagged *X*, marked *X* | `tags` |
| today, yesterday, last week, since Monday, in August | `when` / `createdFrom` / `createdTo` |
| photos, images, videos, PDFs | `mimeType` (`image/`, `video/`, `application/pdf`) |
| newest, oldest, biggest, alphabetical | `sort` |

`text` searches the asset's whole document: filenames, paths, transcripts, extracted
document text, captions, detection labels and tag names are all folded into it. So "the
video where they talk about the harbour" is `text: "harbour"` with `mimeType: "video/"` —
you do **not** need `types: ["transcript"]` for that. Ask for `transcript` only when the
user needs the *timecode* of the passage.

## Rules

- **Never widen a filter to get results.** If `creator: "pete"` finds nothing, that is the
  answer. Re-running without the creator returns everybody's assets, and reporting those as
  Pete's is worse than reporting nothing.
- **Never report "there are none" when the tool said it could not run.** "Search is
  unavailable" and "nothing matched" are different answers.
- **Do not page for its own sake.** Raise `limit` or narrow the query rather than walking
  `offset`.
- `SEMANTIC` and `HYBRID` modes need a `text` and are off in most deployments. Use the
  default `LEXICAL` unless a plain term has already failed and the user wants "things *like*
  this".
- Report what was searched, in the user's words, using the summary the tool returns.

## The other search tools

- `search_assets` — a term and little else. `find_assets` supersedes it; use it only for a
  bare keyword lookup.
- `search_transcript` — spoken-word hits with timecodes, across the catalogue.
- `get_asset` — everything known about one asset, by uuid or sha512.
- `asset_statistics` — counts and totals, when the question is "how many" rather than "which".
- `list_collections` — what collections exist, when the user's wording matches none.
