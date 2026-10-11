-- AURA cloud skill store (Supabase / Postgres)
-- Matches cloud/SupabaseSkillApi.CloudSkill. Run once in the Supabase SQL editor.
-- Safe to re-run: every statement is idempotent.
--
-- One row per skill VARIANT (task × OEM skin × Android version group × app major version),
-- so a OneUI and a MIUI version of the same task live side by side.
--   skill_id  = variant id   (SkillIdentity.variantId)
--   intent_id = task id      (SkillIdentity.generateCanonicalId) — shared by all variants

CREATE TABLE IF NOT EXISTS skill_packs (
    skill_id           TEXT PRIMARY KEY,
    intent_id          TEXT NOT NULL DEFAULT '',
    app_package        TEXT NOT NULL,
    intent_category    TEXT NOT NULL DEFAULT 'unknown',
    intent_action      TEXT NOT NULL DEFAULT 'unknown',
    display_name       TEXT NOT NULL DEFAULT '',

    trigger_keywords   TEXT NOT NULL DEFAULT '[]',   -- JSON array (text, searched with ILIKE)
    parameters         TEXT NOT NULL DEFAULT '[]',   -- JSON array of slot names
    phrase_patterns    TEXT NOT NULL DEFAULT '[]',   -- JSON array, e.g. "send {message_text} to {contact_name} on whatsapp"
    steps              TEXT NOT NULL,                -- JSON array of ActionStep templates
    risk_level         TEXT NOT NULL DEFAULT 'MEDIUM',

    min_api_level      INT  NOT NULL DEFAULT 29,
    max_api_level      INT,
    target_api_level   INT  NOT NULL DEFAULT 29,
    os_class_tag       TEXT NOT NULL DEFAULT 'universal',
    os_skin            TEXT,                         -- NULL = any skin
    skin_version       TEXT,
    device_model       TEXT,
    app_version        TEXT,
    app_major_version  TEXT,                         -- NULL = any app version

    version            INT   NOT NULL DEFAULT 1,
    usage_count        INT   NOT NULL DEFAULT 0,
    success_count      INT   NOT NULL DEFAULT 0,
    failure_count      INT   NOT NULL DEFAULT 0,
    success_rate       REAL  NOT NULL DEFAULT 1.0,
    created_by         TEXT  NOT NULL DEFAULT 'community',
    is_verified        BOOLEAN NOT NULL DEFAULT false,
    downloads          INT   NOT NULL DEFAULT 0,
    description        TEXT  NOT NULL DEFAULT '',
    is_stale           BOOLEAN NOT NULL DEFAULT false,
    last_used_at       BIGINT NOT NULL DEFAULT 0
);

-- Upgrading an existing table from the one-row-per-task design:
ALTER TABLE skill_packs ADD COLUMN IF NOT EXISTS intent_id         TEXT NOT NULL DEFAULT '';
ALTER TABLE skill_packs ADD COLUMN IF NOT EXISTS phrase_patterns   TEXT NOT NULL DEFAULT '[]';
ALTER TABLE skill_packs ADD COLUMN IF NOT EXISTS app_major_version TEXT;
-- The old rule "one row per (app, category, action)" blocks per-OS variants:
ALTER TABLE skill_packs DROP CONSTRAINT IF EXISTS unique_skill;
-- Old rows have no phrase patterns, so phones can't use them safely:
DELETE FROM skill_packs WHERE phrase_patterns = '[]';

CREATE INDEX IF NOT EXISTS skill_packs_intent_idx ON skill_packs (intent_id);
CREATE INDEX IF NOT EXISTS skill_packs_env_idx    ON skill_packs (os_class_tag, os_skin) WHERE NOT is_stale;

-- Anonymous telemetry used by SupabaseSkillApi.recordExecution
CREATE TABLE IF NOT EXISTS skill_executions (
    id          BIGSERIAL PRIMARY KEY,
    skill_id    TEXT NOT NULL,
    outcome     TEXT NOT NULL,
    os_class    TEXT NOT NULL,
    api_level   INT,
    os_skin     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Trust: who may write what ──────────────────────────────────────────────
-- Each row belongs to the account that first uploaded it (the app signs in anonymously).
-- Only that account may change it, so nobody can overwrite a skill other phones rely on.
-- Phones also vet every downloaded skill before running it (skills/CloudSkillVetting.kt).
ALTER TABLE skill_packs ADD COLUMN IF NOT EXISTS owner_id UUID DEFAULT auth.uid();

-- Clients may not mark their own uploads verified, or fake download counts.
CREATE OR REPLACE FUNCTION skill_packs_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('request.jwt.claim.role', true) IS DISTINCT FROM 'service_role' THEN
        IF TG_OP = 'INSERT' THEN
            NEW.owner_id    := auth.uid();
            NEW.is_verified := false;
            NEW.downloads   := 0;
        ELSE
            NEW.owner_id    := OLD.owner_id;
            NEW.is_verified := OLD.is_verified;
            NEW.downloads   := OLD.downloads;
        END IF;
        NEW.created_by := 'community';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS skill_packs_guard ON skill_packs;
CREATE TRIGGER skill_packs_guard BEFORE INSERT OR UPDATE ON skill_packs
    FOR EACH ROW EXECUTE FUNCTION skill_packs_guard();

-- Row-level security: anyone can read; signed-in (incl. anonymous) users can add rows
-- and change only their own.
ALTER TABLE skill_packs ENABLE ROW LEVEL SECURITY;
ALTER TABLE skill_executions ENABLE ROW LEVEL SECURITY;
-- Replace the old "anyone may update anything" policies.
DROP POLICY IF EXISTS skill_packs_write  ON skill_packs;
DROP POLICY IF EXISTS skill_packs_update ON skill_packs;
DO $$ BEGIN
    CREATE POLICY skill_packs_read  ON skill_packs FOR SELECT USING (true);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE POLICY skill_packs_write ON skill_packs FOR INSERT TO authenticated
    WITH CHECK (owner_id IS NULL OR owner_id = auth.uid());
CREATE POLICY skill_packs_update ON skill_packs FOR UPDATE TO authenticated
    USING (owner_id = auth.uid()) WITH CHECK (owner_id = auth.uid());
DO $$ BEGIN
    CREATE POLICY skill_exec_insert ON skill_executions FOR INSERT TO authenticated WITH CHECK (true);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
