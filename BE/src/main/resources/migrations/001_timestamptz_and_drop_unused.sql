-- Migration 001 — за база, създадена с ПРЕДИШНАТА версия на schema.sql.
--
-- Защо е нужен отделен файл: schema.sql ползва CREATE TABLE IF NOT EXISTS,
-- което при вече съществуваща таблица не прави НИЩО — нито сменя типа на
-- колона, нито трие таблици. Затова промените от code review-а
-- (messages.timestamp -> TIMESTAMPTZ, махнатите мъртви таблици) НЕ се
-- прилагат автоматично върху вече деплойната Neon база само защото
-- schema.sql е обновен.
--
-- Пусни го ВЕДНЪЖ срещу съществуваща база (Neon SQL editor или psql -f).
-- Нова, празна база не се нуждае от него — schema.sql вече описва
-- крайното състояние.

BEGIN;

-- MessageDAO вече чете колоната като OffsetDateTime
-- (rs.getObject("timestamp", OffsetDateTime.class)) и я праща по кабела като
-- ISO-8601. Стар TIMESTAMP без timezone не носи offset, затова го
-- интерпретираме като UTC — това е, което CURRENT_TIMESTAMP е записвал на
-- сървъра (Neon compute-ът работи в UTC).
ALTER TABLE messages
    ALTER COLUMN timestamp TYPE TIMESTAMPTZ
    USING timestamp AT TIME ZONE 'UTC';

ALTER TABLE messages
    ALTER COLUMN timestamp SET DEFAULT now();

-- Нито един ред Java код не ги чете или пише — виж code review-а.
DROP TABLE IF EXISTS conversations;
DROP TABLE IF EXISTS user_status;

COMMIT;
