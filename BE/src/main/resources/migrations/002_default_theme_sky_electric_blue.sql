-- Migration 002 — смяна на дефолтната тема на приложението.
--
-- schema.sql описва крайното състояние, но ALTER COLUMN ... SET DEFAULT
-- (както и schema.sql-ния CREATE TABLE IF NOT EXISTS) не се прилага
-- автоматично върху вече съществуваща таблица. Само тая колонна DEFAULT
-- стойност определя каква тема получава един НОВ ред (нова регистрация) —
-- вече съществуващите потребители пазят каквото имат в момента (не пипаме
-- чужди запазени предпочитания).
--
-- Пусни го ВЕДНЪЖ срещу съществуваща база (Neon SQL editor или psql -f).
-- Нова, празна база не се нуждае от него — schema.sql вече описва
-- крайното състояние (виж ChatTheme.java DEFAULT_BACKGROUND_THEME_ID /
-- DEFAULT_BUBBLE_THEME_ID / DEFAULT_UI_THEME_ID за същата смяна на
-- fallback-стойността, използвана от кода).

BEGIN;

ALTER TABLE users ALTER COLUMN bubble_theme SET DEFAULT 'solid_electric_blue';
ALTER TABLE users ALTER COLUMN background_theme SET DEFAULT 'bg_solid_sky';
ALTER TABLE users ALTER COLUMN ui_theme SET DEFAULT 'ui_vivid_electric_blue';

COMMIT;
