-- Migration 002 — changes the app's default theme.
--
-- schema.sql describes the final state, but ALTER COLUMN ... SET DEFAULT
-- (like schema.sql's CREATE TABLE IF NOT EXISTS) doesn't apply
-- automatically to an already-existing table. Only this column's DEFAULT
-- value determines what theme a NEW row gets (a new registration) —
-- already-existing users keep whatever they currently have (we don't touch
-- other people's saved preferences).
--
-- Run this ONCE against an existing database (Neon SQL editor or psql -f).
-- A new, empty database doesn't need it — schema.sql already describes
-- the final state (see ChatTheme.java DEFAULT_BACKGROUND_THEME_ID /
-- DEFAULT_BUBBLE_THEME_ID / DEFAULT_UI_THEME_ID for the same change of
-- the fallback value used by the code).

BEGIN;

ALTER TABLE users ALTER COLUMN bubble_theme SET DEFAULT 'solid_electric_blue';
ALTER TABLE users ALTER COLUMN background_theme SET DEFAULT 'bg_solid_sky';
ALTER TABLE users ALTER COLUMN ui_theme SET DEFAULT 'ui_vivid_electric_blue';

COMMIT;
