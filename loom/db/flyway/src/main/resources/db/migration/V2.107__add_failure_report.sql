-- What a user says went wrong, and the one identifier that lets an operator find it in the log.
--
-- WHY A TABLE AND NOT A LOG LINE. The server already logs every failure, with a stack trace the
-- report can never match for detail. What the log cannot hold is the half only the user has: what
-- they were trying to do, what they expected instead, and what was on their screen. Neither half is
-- actionable alone - a 500 in the log names a path and a cause but not an intent, and "it did not
-- work" names an intent and nothing else. `trace_id` is what joins them, which is why it is the
-- column this table exists to carry and why TraceIdHandler was written before it.
--
-- IT IS NOT A NOTIFICATION AND NOT A COMMENT. `notification` is server-dispatched and addressed to
-- one recipient; this is user-authored and addressed to whoever operates the instance. `comment`
-- hangs off an asset and is read by the chat agent as trustworthy input, which is precisely what a
-- free-text field full of error strings from an unknown client must never become.
--
-- EVERY FIELD DESCRIBING THE REQUEST IS NULLABLE, on purpose. The failures worth reporting include
-- ones that never produced a response: a render throw caught by the UI's error boundary, a
-- WebSocket that closed, a screen that stayed empty. Requiring status_code would mean the reports
-- hardest to reproduce are the ones the form refuses to accept.
CREATE TABLE "failure_report" (
  "uuid"          uuid NOT NULL DEFAULT uuidv7 (),

  -- What the user was doing, in the client's own vocabulary - "createPerson", "deleteTag",
  -- "loadLibraries". Stamped by the UI at the call site rather than derived from the path, because
  -- the path answers "which route" and this has to answer "which button", and one route serves
  -- several buttons.
  "action"        varchar NOT NULL,

  -- The request that failed, as the CLIENT saw it. Deliberately not re-derived server-side: the
  -- report is a record of the user's experience, and if the two ever disagree that disagreement is
  -- itself the finding.
  "trace_id"      varchar,
  "http_method"   varchar,
  "path"          varchar,
  "status_code"   integer,

  -- The message the client showed the user. Kept verbatim so the report can be matched against the
  -- screenshot, and never interpreted: it is attacker-influencable text in the general case.
  "error_message" varchar,

  -- Where in the UI the user was standing - the react-router path, e.g. "/detection".
  "route"         varchar,

  -- Stamped SERVER-SIDE from the request headers, not read from the body. A client is free to lie
  -- about its own user agent, and a report whose provenance is self-declared is worth less than one
  -- whose provenance is observed. The same argument does not apply to the fields above: those
  -- describe an earlier request this one is reporting on, which the server cannot observe at all.
  "user_agent"    varchar,

  -- What the user typed. The only field in the table that is prose, and the reason the whole
  -- feature exists.
  "text"          varchar,

  -- Triage state. varchar + CHECK per V2.55, so adding a value later costs a DROP plus an ADD
  -- rather than a DO block.
  --
  -- NOT called "status", which is what every other table in this schema would have called it. The REST
  -- response base class AbstractCreatorEditorRestResponse already owns a `status` property - the
  -- creator/editor audit block - so a field of that name here cannot be expressed in the API at all.
  -- Naming the column after the name the API can actually use keeps the two halves readable together.
  "triage_status" varchar NOT NULL DEFAULT 'NEW',

  "created"       timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "creator_uuid"  uuid,
  "edited"        timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "editor_uuid"   uuid,

  CONSTRAINT "failure_report_pkey" PRIMARY KEY ("uuid"),

  CONSTRAINT "failure_report_triage_status_check" CHECK ("triage_status" IN ('NEW', 'ACKNOWLEDGED', 'RESOLVED')),

  -- A status code that is not one is a client bug, and storing it would put nonsense in the inbox's
  -- only filterable numeric column.
  CONSTRAINT "failure_report_status_code_check" CHECK (
    "status_code" IS NULL OR ("status_code" >= 100 AND "status_code" <= 599)),

  -- SET NULL, matching `share` (V2.97) and `notification` (V2.70) rather than the CASCADE most
  -- creator FKs take: deleting the person who reported a bug must not delete the bug. The reporter
  -- becomes anonymous, the finding survives, and the trace id still resolves.
  CONSTRAINT "failure_report_creator_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid") ON DELETE SET NULL,
  CONSTRAINT "failure_report_editor_fkey"  FOREIGN KEY ("editor_uuid")  REFERENCES "user" ("uuid") ON DELETE SET NULL
);

-- The inbox reads newest-first and filters by triage state; the trace lookup goes the other way,
-- from an id found in a log line back to whoever reported it.
CREATE INDEX "idx_failure_report_triage_created" ON "failure_report" ("triage_status", "created" DESC);
CREATE INDEX "idx_failure_report_created"        ON "failure_report" ("created" DESC);
CREATE INDEX "idx_failure_report_trace"          ON "failure_report" ("trace_id") WHERE "trace_id" IS NOT NULL;
CREATE INDEX "idx_failure_report_creator"        ON "failure_report" ("creator_uuid");

-- The screenshot, in a table of its own.
--
-- WHY NOT A COLUMN ON `failure_report`. Postgres would TOAST it out of line anyway, but the read
-- pattern is what decides it: the inbox lists reports and never wants the bytes, and `SELECT *` is
-- what AbstractJooqDao issues. A megabyte-wide column on the listed table turns every page of the
-- inbox into a multi-megabyte read for data no caller asked for.
--
-- WHY NOT BINARY STORAGE. `attachment` puts bytes in a storage pool, and reusing that here would
-- have been the tidier-looking choice. It is the wrong one for this table specifically: a failure
-- report has to be storable when storage is the thing that is broken. An unreachable bucket is one
-- of the failures a user most needs to report, and a report path that writes to the bucket first
-- would fail exactly then. bytea keeps the report in the same transaction as the row it belongs to,
-- which is the only arrangement that holds under the conditions this feature is for.
--
-- Size is capped in FailureReportEndpointService, not here: a CHECK on octet_length() would reject
-- an oversized upload with a constraint violation the user cannot act on, where the service can
-- answer 413 and say what the limit is.
CREATE TABLE "failure_report_screenshot" (
  "report_uuid" uuid NOT NULL,

  "mime_type"   varchar NOT NULL,
  "width"       integer,
  "height"      integer,
  "size"        bigint NOT NULL,
  "data"        bytea NOT NULL,

  "created"     timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),

  CONSTRAINT "failure_report_screenshot_pkey" PRIMARY KEY ("report_uuid"),

  -- One screenshot per report, enforced by the primary key rather than a unique index: the
  -- relationship is 1:1 and the report uuid is the natural key.
  CONSTRAINT "failure_report_screenshot_report_fkey" FOREIGN KEY ("report_uuid") REFERENCES "failure_report" ("uuid") ON DELETE CASCADE
);

COMMENT ON TABLE  "failure_report" IS 'A problem report submitted from the UI. Carries the half of a failure the server log cannot hold - what the user was doing and what they expected - joined to the log by trace_id';
COMMENT ON COLUMN "failure_report"."trace_id" IS 'The X-Trace-Id of the failing response, stamped by TraceIdHandler. The join key between this report and the stack trace that produced it';
COMMENT ON COLUMN "failure_report"."action" IS 'What the user was doing, in the client vocabulary ("createPerson"). Answers "which button", where path answers "which route"';
COMMENT ON COLUMN "failure_report"."user_agent" IS 'Stamped server-side from the request headers, not read from the body: observed provenance is worth more than self-declared';
COMMENT ON COLUMN "failure_report"."creator_uuid" IS 'Nullable ON DELETE SET NULL: deleting the person who reported a bug must not delete the bug';
COMMENT ON COLUMN "failure_report"."status_code" IS 'HTTP status of the failing response, or NULL for failures that never produced one - a render throw, a closed socket, a screen that stayed empty';

COMMENT ON TABLE  "failure_report_screenshot" IS 'The optional screenshot attached to a report. bytea in its own table, not binary storage: a report must be storable when storage is the thing that is broken, and the inbox listing must not read megabytes it never shows';
