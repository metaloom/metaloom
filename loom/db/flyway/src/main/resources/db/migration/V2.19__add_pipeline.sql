CREATE TABLE pipeline (
    uuid           UUID NOT NULL DEFAULT uuid_generate_v4(),
    name           VARCHAR NOT NULL,
    description    VARCHAR,
    definition     JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled        BOOLEAN NOT NULL DEFAULT true,
    priority       INTEGER NOT NULL DEFAULT 0,
    dry_run        BOOLEAN NOT NULL DEFAULT false,
    meta           JSONB,
    created        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    creator_uuid   UUID NOT NULL,
    edited         TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    editor_uuid    UUID NOT NULL,
    CONSTRAINT pipeline_pkey PRIMARY KEY (uuid),
    CONSTRAINT pipeline_creator_uuid_fkey FOREIGN KEY (creator_uuid) REFERENCES "user" (uuid),
    CONSTRAINT pipeline_editor_uuid_fkey FOREIGN KEY (editor_uuid) REFERENCES "user" (uuid)
);
