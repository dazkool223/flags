-- flagkit schema for one environment. ${p} is the table prefix (validated before substitution).
-- Run as the login that should own the tables (or as the owner role), then run roles.sql.

CREATE TABLE ${p}flag (
    flag_key       text PRIMARY KEY,
    enabled        boolean NOT NULL DEFAULT false,
    match_mode     text NOT NULL DEFAULT 'ALL' CHECK (match_mode IN ('ALL', 'ANY')),
    condition_name text
);

-- position keeps prerequisite order stable: ALL/ANY stop at the first decisive one.
CREATE TABLE ${p}prerequisite (
    flag_key         text NOT NULL REFERENCES ${p}flag (flag_key),
    prerequisite_key text NOT NULL REFERENCES ${p}flag (flag_key),
    position         integer NOT NULL,
    PRIMARY KEY (flag_key, prerequisite_key),
    CHECK (flag_key <> prerequisite_key)
);

CREATE INDEX ${p}prerequisite_by_prerequisite ON ${p}prerequisite (prerequisite_key);

CREATE TABLE ${p}param (
    flag_key text NOT NULL REFERENCES ${p}flag (flag_key),
    name     text NOT NULL,
    value    text NOT NULL,
    PRIMARY KEY (flag_key, name)
);

-- One seeded row. Writers lock it first, which serializes every write to this environment.
CREATE TABLE ${p}meta (
    id       smallint PRIMARY KEY CHECK (id = 1),
    revision bigint NOT NULL
);

INSERT INTO ${p}meta (id, revision) VALUES (1, 0);

-- Append-only. The actor and time come from the database, never from the caller.
CREATE TABLE ${p}audit (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    actor        text NOT NULL DEFAULT session_user,
    operation    text NOT NULL,
    flag_key     text NOT NULL,
    before_value jsonb,
    after_value  jsonb
);
