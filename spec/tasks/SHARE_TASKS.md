# Share System — Task List

> Work items for the customer-facing share area, derived from a code audit on 2026-08-11 — the day
> both phases shipped. Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../features/share/SHARE_SYSTEM.md](../features/share/SHARE_SYSTEM.md) (technical spec) ·
> [../loom/RESTAPI.md](../loom/RESTAPI.md) · [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) ·
> [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md)
>
> **The feature works end to end.** Everything below is a gap in what an *owner* can see and operate,
> or an operational edge the first real deployment will meet — not a defect in what a customer
> experiences.
>
> **Ordering.** Tasks 1–3 are the blocking cluster and should be done in that order: the server
> already collects the customer's feedback, the bell already announces it, and there is nowhere in
> the product to read it. Task 1 gives it a home, Task 2 gives the links a home, and Task 3 makes the
> bell point at both. Tasks 4–5 are operational and independent. Tasks 6–9 are improvements.
>
> **Six owner-side client functions are already written, typed, and called by nothing:**
> `loadShareFeedback`, `listShareLinks`, `deleteShareLink`, `listAssetShareLinks`,
> `listCollectionShareLinks` in [`loom-ui/src/api/shareLinks.ts`](../../loom-ui/src/api/shareLinks.ts),
> plus `isoToExpiry` in [`shareExpiry.ts`](../../loom-ui/src/features/share/shareExpiry.ts) whose only
> caller is its own test. Tasks 1 and 2 consume all six. Anything still unused after both are done is
> dead code and should be deleted rather than left as a promise.

---

## Task 1: Show the customer's feedback inside the product

**Argumentation Summary:** The whole point of Phase 2 is that a client's notes come back attached to
the material — and today they land in the database and stop there. `GET /share-links/:uuid/feedback`
is built, tested (`PublicShareFeedbackEndpointTest#testOwnerReadsTheFeedback`) and exposed through
all three clients, but `loadShareFeedback` in `loom-ui/src/api/shareLinks.ts` has no caller.
`NotificationDispatcher#shareFeedbackLeft` fires a `SHARE_FEEDBACK` bell entry whose `assetUuid`
routes to `/assets/:uuid` via `notificationLink.ts` — so the owner is told somebody commented,
clicks, arrives at the asset, and finds nothing. The internal Comments tab shows internal comments
only, correctly, because guest feedback lives in its own tables.

**Improvement Summary:** Add a *Feedback* tab to `AssetDetail` that lists the guest comments, marks
and reactions left through every link pointing at that asset, visibly separated from internal
collaboration.

```
1. loom-ui/src/api/shareLinks.ts already exports listAssetShareLinks and loadShareFeedback. No
   client change is needed.

2. Add a feature module loom-ui/src/features/assetDetail/ShareFeedbackTab.tsx:
   - Props: { assetUuid: string }.
   - On mount: listAssetShareLinks(token, assetUuid) -> for each returned share with
     feedbackCount > 0, loadShareFeedback(token, share.uuid). Fetch the shares in parallel; a
     share whose feedback call fails must not blank the tab.
   - Render one group per share link, headed by share.visitorName (or an "Unopened" chip when it
     is absent) plus share.targetName and the link's expiry state.
   - Within a group render, in this order: reactions as chips, annotations with their timecode,
     comments threaded one level deep. Reuse groupComments and formatTimecode from
     features/share/shareExpiry.ts rather than writing a second threading pass.
   - Every group must be visually marked as EXTERNAL. This is not decoration: an outside party's
     opinion must never be mistaken for a colleague's note. Use a distinct border/label, not just
     a different avatar.
   - Clicking an annotation seeks the asset's own player when one exists; otherwise the timecode
     is text.

3. In loom-ui/src/features/assetDetail/AssetDetail.tsx:
   - Add to the `tabs` array (around line 759), conditionally like the `faces` tab already is:
     ...(shareFeedbackCount > 0 ? [{ label: tAD("tab.shareFeedback", { count }), icon:
     <ShareOutlined sx={{ fontSize: 14 }} /> }] : [])
   - Hiding the tab when there is nothing rather than showing an empty one matches the faces tab
     and keeps the tab strip honest.
   - Render <ShareFeedbackTab assetUuid={asset.id} /> in the matching tab panel.

4. i18n: add assetDetail.tab.shareFeedback and the ShareFeedbackTab strings to BOTH
   src/i18n/locales/en.json and de.json.

5. Do NOT surface this text to the chat agent - see Task 9.
```

**References:** [../features/share/SHARE_SYSTEM.md](../features/share/SHARE_SYSTEM.md) §7, §13 (Open) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §10 · `PublicShareFeedbackEndpointTest#testOwnerReadsTheFeedback`
**Test Requirements:** A new `loom-ui/e2e/share-feedback-tab-mocked.spec.ts`: the tab is absent when
no link has feedback; present and grouped per link when it does; guest entries are distinguishable
from internal comments. Extend `shareExpiry.test.ts` only if new pure helpers appear. Run
`cd loom-ui && ./node_modules/.bin/vitest run && ./node_modules/.bin/playwright test e2e/share-feedback-tab-mocked.spec.ts`
(never `npx` — it hangs).

---

## Task 2: A screen for managing share links

**Argumentation Summary:** Links can be created and are then unmanageable from the product. There is
no list of them, no way to revoke one, and no way to see that a client has not opened the link you
sent last week — `viewCount`, `visitorName`, `lastViewedAt` and `expired` are all on `ShareResponse`
and rendered nowhere. `listShareLinks`, `deleteShareLink`, `listCollectionShareLinks` and
`isoToExpiry` were written for this screen and currently have no caller at all. The practical
consequence is that revoking a leaked link requires the REST API, which is exactly the moment a user
will not want to reach for `curl`.

**Improvement Summary:** An admin screen listing every share link with its target, visitor, activity
and expiry, able to revoke one and to edit an existing one instead of always minting a new one.

```
1. New file loom-ui/src/features/admin/ShareLinksAdmin.tsx. Its OWN file, not a section of
   AdminArea.tsx - LOOM_UI.md §4.2 records that a new admin screen gets its own file and that
   AdminArea.tsx (~1.6k lines) should not grow further. SearchIndicesAdmin.tsx is the precedent.

2. Route and tab: add the ADMIN_TABS entry and the <Route path="share-links"> in
   features/admin/AdminArea.tsx, following exactly what `indices` does there.

3. The table, using usePagedList + listShareLinks (it is already registered in the table-driven
   src/api/listPaging.test.ts, so paging is covered):
   - Target (targetName + a chip for ASSET/COLLECTION, linking to the asset or collection)
   - Visitor (visitorName, or an "Unopened" chip - the distinction is the useful one)
   - Activity (viewCount, lastViewedAt, feedbackCount)
   - State (expired -> a StatusChip in red; passwordProtected -> a lock icon)
   - Owner (status.creator, rendered as "-" when null: deleting a user does not delete their
     shares, so an ownerless link is a NORMAL state here, not an error)
   - Actions: copy link, edit, revoke

4. Edit reuses features/share/ShareDialog.tsx. Extend it with an optional `share?: ShareResponse`
   prop: when present it edits that link instead of creating one on open, seeding the expiry
   selector with isoToExpiry(share.expiresAt) - which is why that helper exists. The password
   field must show "unchanged" rather than a value, because the server returns the password
   exactly once and this screen cannot know it.

5. Revoke asks for confirmation and says plainly that the URL stops working immediately and that
   the feedback left through it is deleted with it. Both are true (V2.99 cascades) and neither is
   guessable from the button.

6. Sidebar: no entry of its own. Reached through the AdminArea tab bar, like `indices`,
   `db-integrity` and `storage`.

7. i18n in BOTH locale files.
```

**References:** [../features/share/SHARE_SYSTEM.md](../features/share/SHARE_SYSTEM.md) §13 (Open) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §4.2, §10 (`SearchIndicesAdmin` precedent) ·
migration `V2.97__add_share.sql` (why `creator_uuid` may be null)
**Test Requirements:** `loom-ui/e2e/share-links-admin-mocked.spec.ts` covering: the list renders an
unopened and an opened link differently; an expired link is marked; revoke calls `DELETE` and
confirms first; an ownerless link renders without error. Deep-link to `/ui/admin/share-links` **and
then** sign in — auth is in-memory, so a `goto` after signing in returns to the login form. Run with
`./node_modules/.bin/playwright test e2e/share-links-admin-mocked.spec.ts`.

---

## Task 3: Make a SHARE_FEEDBACK notification land somewhere useful

**Argumentation Summary:** `NotificationDispatcher#shareFeedbackLeft` sets only `assetUuid`, because
`notification` has no share reference. Two consequences, both live today: feedback on a **collection**
share carries no subject at all, so `notificationLink()` returns null and the bell entry is inert —
V2.70's own comment calls a notification with no reachable subject worse than none; and feedback on
an asset share deep-links to `/assets/:uuid`, which shows nothing relevant until Task 1 lands.

**Improvement Summary:** Give the notification a share reference and route it at the feedback,
rather than at the nearest thing that happened to have a uuid.

```
1. Migration V2.<next>__notification_share_subject.sql (re-check the highest V2.* first - the
   numbers in this file will be stale):
   - ALTER TABLE "notification" ADD COLUMN "share_uuid" uuid;
   - FK -> "share"("uuid") ON DELETE CASCADE, matching every other subject FK in V2.70: a bell
     entry whose subject is gone deep-links to a 404, which is worse than no entry.
   - CREATE INDEX "idx_notification_share" ON "notification" ("share_uuid") - Postgres does not
     index the referencing side, and V2.70 carries one index per subject FK for exactly this.
   - COMMENT ON COLUMN explaining that this is the only subject that is not a Loom entity the
     recipient owns.

2. Run loom/db/jooq/generate.sh, then ./setup-pool.sh.

3. Model + DAO: add shareUuid to Notification/NotificationImpl and to
   NotificationModelBuilder/NotificationResponse.

4. NotificationDispatcher#shareFeedbackLeft: set n.setShareUuid(share.getUuid()) alongside the
   existing assetUuid.

5. loom-ui/src/features/notifications/notificationLink.ts: add a shareUuid branch. Order matters
   and is documented there - most specific wins. A share notification should route to the asset
   when it has one (Task 1 gives that a Feedback tab) and to the share admin screen (Task 2)
   otherwise, so a collection share is no longer inert.

6. Update the notifications empty-state copy in both locale files: it currently promises
   "Assignments, comments and run failures", which no longer covers what arrives.
```

**References:** migration `V2.70__add_notification.sql` (subject-FK cascade and index rationale) ·
[../features/share/SHARE_SYSTEM.md](../features/share/SHARE_SYSTEM.md) §7.3 ·
`loom-ui/src/features/notifications/notificationLink.ts`
**Test Requirements:** Extend `notificationLink.test.ts` with the share cases including the
collection share (previously null). Extend `NotificationDispatchTest` to assert a `SHARE_FEEDBACK`
row carries `share_uuid`. A delete-cascade case: deleting the share removes its notifications.
`mvn test -pl loom/core -Dtest=NotificationDispatchTest` and `mvn test -pl loom/db/jooq -Dtest=ShareCascadeTest`.

---

## Task 4: Sweep expired and orphaned share links

**Argumentation Summary:** Nothing ever removes a share row. An expired link answers 404 and is
invisible to its owner, but it and all of its guest feedback stay in the database indefinitely — and
`share_comment`/`share_annotation`/`share_reaction` are the only tables in this feature that an
outside party can grow. An installation that shares material routinely will accumulate rows nobody
can reach and nobody has agreed to keep. There is also no retention statement anywhere, which is a
question a customer will eventually ask about their own comments.

**Improvement Summary:** A scheduled reaper that deletes shares which expired longer than a grace
period ago, with the grace period configurable and the deletion logged.

```
1. New loom/services/rest/.../service/impl/ShareReaper.java, modelled on the existing
   LeaseReaper and ProcessorPresenceReaper in the same package (both are periodic vertx timers
   started from RESTService).

2. Behaviour:
   - Every LOOM_SHARE_REAP_INTERVAL (default 1h), delete shares where expires_at < now() - grace.
   - Grace period LOOM_SHARE_RETENTION_DAYS, default 30. NOT zero: an owner who let a link lapse
     and then wants to read the feedback it collected must have a window, and "the review expired
     so we deleted the review" is the wrong default.
   - The feedback goes with the share through the V2.99 cascade; no second query.
   - Log the count at INFO. A silent sweep of customer-authored rows is not acceptable.

3. Add a ShareDao#deleteExpiredBefore(Instant) issuing ONE delete, not a load-then-loop.

4. Register the reaper in RESTService alongside leaseReaper and presenceReaper.

5. Add both options to AuthenticationOptions' neighbour - wherever the other reaper intervals
   live - and to the environment-variable table in
   ../features/share/SHARE_SYSTEM.md §9, which currently says the feature adds none.

6. Document the retention window in website/content/english/docs/loom/sharing/index.adoc: a
   customer whose comments are deleted after 30 days should be able to find that out.
```

**References:** `LeaseReaper.java`, `ProcessorPresenceReaper.java` (shape and registration) ·
migration `V2.99__add_share_feedback.sql` (the cascade this relies on) ·
[../features/share/SHARE_SYSTEM.md](../features/share/SHARE_SYSTEM.md) §9
**Test Requirements:** `ShareReaperTest` in `loom/core`: a share expired inside the grace period
survives; one past it is deleted with its feedback; a share with no expiry is never touched. Add the
never-expiring case explicitly — it is the one an off-by-one would silently delete.
`mvn test -pl loom/core -Dtest=ShareReaperTest`.

---

## Task 5: Make the share password throttle work across processes

**Argumentation Summary:** `ShareThrottle` is a `ConcurrentHashMap` in one JVM. An installation
running several REST nodes behind a load balancer multiplies the allowance by the node count, so the
documented "10 attempts per 15 minutes" is really 10 × N and the guard weakens exactly as a
deployment grows. This is stated honestly in the spec and in the class comment rather than hidden,
but it remains the one security control in the feature that does not hold under the deployment shape
the Helm chart supports.

**Improvement Summary:** Move the counter behind a small interface with the in-memory implementation
as the default and a shared-store implementation for multi-node installations.

```
1. Extract the two methods ShareThrottle exposes into an interface (isThrottled / recordFailure /
   recordSuccess), keeping the current class as the default binding. Do not change the semantics:
   per-slug, not per-client-address, for the reasons in its javadoc.

2. Add a Postgres-backed implementation. The database is already a hard dependency, so this needs
   no new infrastructure - unlike Redis, which would.
   - A small table, or a single UPDATE ... RETURNING against a counter column, whichever keeps it
     to one round trip. This runs on an unauthenticated path, so it must not become the expensive
     part of a request that is refused anyway.
   - Prune lapsed rows in the Task 4 reaper rather than adding a second timer.

3. Select the implementation from configuration, defaulting to in-memory so a single-node
   installation pays nothing.

4. Update ../features/share/SHARE_SYSTEM.md §3.3 and the Open list in §13, both of which currently
   describe the limitation as accepted.
```

**References:** [../features/share/SHARE_SYSTEM.md](../features/share/SHARE_SYSTEM.md) §3.3, §13 ·
`ShareThrottle.java` · [../features/helm/HELM_LOOM.md](../features/helm/HELM_LOOM.md) (the multi-replica deployment shape this weakens under)
**Test Requirements:** `ShareThrottleTest` covering the contract against both implementations
(parameterised): the allowance is exhausted, the window lapses, a success resets. Plus an endpoint
case that two separate `LoomHttpClient` instances share one budget when the shared implementation is
bound. `mvn test -pl loom/core -Dtest=ShareThrottleTest`.

---

## Task 6: Let a customer edit what they wrote

**Argumentation Summary:** `POST /shares/:slug/comments/:uuid` and
`POST /shares/:slug/annotations/:uuid` are implemented, permission-gated, covered by
`PublicShareFeedbackEndpointTest#testCommentRoundTrip`, and exposed in the Java, Python and
TypeScript clients — and the viewer never calls them. A customer who makes a typo can only delete
and retype, and cannot move a box they drew slightly wrong. `updateSharedComment` and
`updateSharedAnnotation` in `loom-ui/src/api/shares.ts` have no caller.

**Improvement Summary:** Inline editing for a guest comment, and drag-to-move/resize for a drawn
region, using the endpoints that already exist.

```
1. loom-ui/src/features/share/ShareFeedbackPanel.tsx: add an edit affordance to CommentRow beside
   the existing delete, switching the row to a TextField seeded with the current text. Save calls
   the onEditComment prop; Escape cancels.

2. loom-ui/src/features/share/ShareViewer.tsx: implement onEditComment via updateSharedComment,
   then refreshFeedback(), with the same handleError treatment as the other writes.

3. ShareRegionOverlay.tsx: when a box is selected, render corner handles and allow dragging the
   box or a handle. Emit the same NormalisedRegion shape and call updateSharedAnnotation.
   - Reuse the existing clamping so a drag off the edge still lands inside 0..1.
   - The kind cannot change: the server refuses it (PublicShareEndpointService#updateAnnotation)
     because stored geometry would then contradict the declared kind. Move and resize only.

4. Note in the panel that everyone holding the link can edit everything written through it. That
   is the accepted consequence of one-name-per-link, and an edit button makes it visible for the
   first time.
```

**References:** [../features/share/SHARE_SYSTEM.md](../features/share/SHARE_SYSTEM.md) §2.2, §7.2 ·
`PublicShareEndpointService#updateComment`, `#updateAnnotation`
**Test Requirements:** Extend `loom-ui/e2e/share-mocked.spec.ts`: a comment can be edited and the
`POST` carries the new text; a region can be dragged and the `POST` carries changed coordinates but
the same `kind`. Run `./node_modules/.bin/playwright test e2e/share-mocked.spec.ts`.

---

## Task 7: A backend Playwright spec for the share area

**Argumentation Summary:** The share area has fourteen mocked specs and no `*-backend.spec.ts`.
Mocked specs cannot catch the two things most likely to break this feature in a real deployment:
the `loom_share_session` cookie actually reaching `<video src>` (which depends on same-origin and on
`VITE_API_BASE_URL=/api/v1` at build time), and `Range`/206 seeking through
`AssetBinaryEndpointService#streamPrimaryBinary`. Both are asserted only at the Java layer today,
where no browser is involved.

**Improvement Summary:** A backend spec that opens the seeded demo share links in a real browser
against a real server.

```
1. New loom-ui/e2e/share-backend.spec.ts, following the existing *-backend.spec.ts conventions.

2. Use the demo links seeded by DemoDatabaseInitializer#seedDemoShares - they exist precisely so
   this is possible without fixture setup:
   - demoOpenCollection001 (open collection, downloadable, feedback on)
   - demoLockedAssetLink01 (password amber-lantern-42, no downloading)

3. Assert:
   - The open link renders its grid without any sign-in.
   - The locked link refuses a wrong password and accepts the right one.
   - Opening a video issues a request that answers 206 (watch via page.on("response")), which is
     what proves seeking works end to end.
   - The download button on the open link yields a file, and is absent on the locked one.
   - The seeded feedback from "Maria from Acme" is visible.

4. Run it the way the other backend specs are run:
   VITE_API_BASE_URL=/api/v1 VITE_PROXY_TARGET=http://localhost:8092 \
     ./node_modules/.bin/playwright test e2e/share-backend.spec.ts
   Note the VITE_API_BASE_URL: a cross-origin base silently breaks the session cookie, and this
   spec is the only thing that would catch that.
```

**References:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §8.2 (backend spec conventions, the
same-origin cookie gotcha) · `DemoDatabaseInitializer#seedDemoShares`
**Test Requirements:** The spec itself. It needs `./start-postgres.sh && ./start-demo.sh` first;
say so at the top of the file, as the other backend specs do.

---

## Task 8: Let the agent create a share link (gap N20)

**Argumentation Summary:** N20 in [../chat/CHAT_USER_REQUESTS.md](../chat/CHAT_USER_REQUESTS.md) is
the request *"Send Maria a link to these five"*, and it is now only half closed: the share model
exists and is reachable over REST, but no MCP tool and no chat skill can create one, so the agent
still cannot answer the sentence that motivated the feature.

**Improvement Summary:** An MCP tool that creates a share link over an asset or collection and
returns the URL, so the agent can hand one over in conversation.

```
1. New loom/services/mcp/.../tool/impl/CreateShareLinkTool.java, modelled on
   ListCollectionsTool.java in the same package; register it in
   loom/services/mcp/.../dagger/MCPToolModule.java.

2. Parameters: targetType, targetUuid, optional expiry choice, optional password. Return the
   absolute URL and the password when one was generated - the password is returned exactly once
   by the server and there is no second chance to read it.

3. ⚠️ The MCP server has NO auth layer and hits the DAOs directly (RESTAPI.md §4.7). A tool that
   mints public URLs is therefore a different proposition from one that lists collections: it
   creates a capability that leaves the installation. Decide explicitly, and record the decision
   in ../features/share/SHARE_SYSTEM.md, whether this tool:
   (a) is gated behind the existing MCP auth options (LOOM_MCP_AUTH_ENABLED), or
   (b) goes through the REST endpoint as an authenticated caller rather than the DAO, so
       CREATE_SHARE and the read-the-target check still apply.
   (b) is the safer default and keeps one authorization path for share creation. Do not skip this
   step: an unauthenticated tool that publishes assets is a larger hole than the feature closes.

4. Update N20 in ../chat/CHAT_USER_REQUESTS.md once done - it currently records the agent-side
   half as the remaining gap.
```

**References:** [../chat/CHAT_USER_REQUESTS.md](../chat/CHAT_USER_REQUESTS.md) (N20, request 64) ·
[../loom/MCP.md](../loom/MCP.md) · [../loom/RESTAPI.md](../loom/RESTAPI.md) §4.7 (the MCP server has
no auth) · [../features/share/SHARE_SYSTEM.md](../features/share/SHARE_SYSTEM.md) §4
**Test Requirements:** A tool test alongside the existing MCP tool tests, asserting a link is
created over the right target and that the chosen authorization path is enforced — specifically that
a caller who may not read the target cannot share it.

---

## Task 9: Mark guest feedback untrusted before the agent can read it

**Argumentation Summary:** Preventive, and cheap now. `share_comment.text` is written by anyone
holding a link — it is the most attacker-controllable text in the schema.
[../chat/AGENTIC_CHAT_PLAN.md](../chat/AGENTIC_CHAT_PLAN.md) already flags internal comments as a
prompt-injection surface once the agent reads them; guest comments make that concrete. Nothing feeds
them to the agent today, and the risk is that Task 1 puts them on a screen, someone later adds
"summarise the feedback on this asset", and the provenance is gone by then.

**Improvement Summary:** Record the rule where it will be read before it is broken, and give the
search and agent paths an explicit exclusion rather than an accidental one.

```
1. Confirm and pin the current state: guest feedback is NOT in search_document (V2.58/V2.59
   triggers cover asset, transcript, annotation, tag, person, collection, library, cluster - the
   share tables are not among them). Add a test that asserts a share_comment does not appear in
   search results, so a future trigger cannot pull it in silently.

2. Add a section to ../features/share/SHARE_SYSTEM.md stating that share_comment,
   share_annotation.text and share_reaction are untrusted input, and that any path exposing them
   to the agent must mark them as such at the boundary.

3. If and when they are exposed: wrap them in the same untrusted-content framing the chat spec
   defines, carrying the author name as data rather than as an instruction, and never inside a
   system prompt.

4. Cross-reference from ../chat/AGENTIC_CHAT_PLAN.md so the warning is reachable from the side
   that would introduce the problem.
```

**References:** [../chat/AGENTIC_CHAT_PLAN.md](../chat/AGENTIC_CHAT_PLAN.md) (comments as an
injection surface) · [../features/search/SEARCH.md](../features/search/SEARCH.md) §6.1 ·
migration `V2.99__add_share_feedback.sql` (header states the same argument)
**Test Requirements:** A search test asserting guest comment text is not indexed. If an
agent-facing path is added, a test that the content arrives marked untrusted.

---

_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (initial task list — written the day the share system shipped; nine open
items, none of which block the feature working for a customer)_
