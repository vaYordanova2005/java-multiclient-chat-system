SELECT user, host
FROM mysql.user;

ALTER USER 'chatapp_user'@'%' IDENTIFIED BY 'messenger2';
FLUSH PRIVILEGES;

GRANT ALL PRIVILEGES ON chatdb.* TO 'chatapp_user'@'%';
FLUSH PRIVILEGES;

SHOW GRANTS FOR 'chatapp_user'@'%';

SELECT User, Host
FROM mysql.user
WHERE User = 'chatapp_user';

CREATE USER 'chatapp_user'@'localhost' IDENTIFIED BY 'messenger2';
GRANT ALL PRIVILEGES ON chatdb.* TO 'chatapp_user'@'localhost';
FLUSH PRIVILEGES;

SELECT User, Host
FROM mysql.user
WHERE User = 'chatapp_user';

use chatdb;
CREATE TABLE messages (
    id INT AUTO_INCREMENT PRIMARY KEY,

    sender VARCHAR(50) NOT NULL,
    receiver VARCHAR(50),         -- NULL ако е public room

    room VARCHAR(100) NOT NULL,   -- general или dm_...

    message TEXT NOT NULL,

    type VARCHAR(20) DEFAULT 'message',

    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE conversations (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user1 VARCHAR(50) NOT NULL,
    user2 VARCHAR(50) NOT NULL,
    room VARCHAR(100) UNIQUE NOT NULL
);
CREATE TABLE user_status (
    username VARCHAR(50) PRIMARY KEY,
    last_seen DATETIME
);

USE chatdb;

-- 🎨 МИГРАЦИЯ 1: Постоянен цвят на потребител
-- Добавяме колона 'color' в users — цвета вече се генерира еднократно
-- при регистрация и се пази завинаги, не се губи при рестарт на сървъра.
ALTER TABLE users ADD COLUMN color VARCHAR(7) DEFAULT NULL;

-- Запълваме цвят на вече съществуващите потребители (тези, които си тествал досега),
-- за да няма NULL цветове за стари акаунти. Всеки извикване генерира различен
-- случаен HEX цвят за реда, който се обновява.
UPDATE users
SET color = CONCAT('#', LPAD(HEX(FLOOR(RAND() * 16777215)), 6, '0'))
WHERE color IS NULL;


-- 🌍 МИГРАЦИЯ 2: Преименуване на стаята 'general' -> 'global'
-- Преместваме старата история, за да не се загуби при смяна на room идентификатора.
UPDATE messages SET room = 'global' WHERE room = 'general';

-- Ако вече имаш записи в conversations с user1/user2 за 'general' (по принцип не би
-- трябвало, но за пълнота):
UPDATE conversations SET room = 'global' WHERE room = 'general';

use chatdb;
CREATE TABLE friendships (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_a VARCHAR(50) NOT NULL,
    user_b VARCHAR(50) NOT NULL,
    requested_by VARCHAR(50) NOT NULL,
    status ENUM('pending', 'accepted') DEFAULT 'pending',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_pair (user_a, user_b)
);

use chatdb;
-- 🎨 МИГРАЦИЯ: Потребителски настройки за теми (постоянни, не само за сесията)
-- bubble_theme  — кой от 10-те ChatTheme.BubbleTheme е избран (по id)
-- bg_gradient_size — колко цвета (2/3/4) да съдържа фоновия градиент на приложението

ALTER TABLE users
    ADD COLUMN bubble_theme VARCHAR(30) DEFAULT 'solid_periwinkle',
    ADD COLUMN bg_gradient_size INT DEFAULT 3;

-- Попълваме дефолтни стойности на вече съществуващите акаунти (за всеки случай,
-- ако DEFAULT не се приложи към вече вмъкнати редове в твоята MySQL версия)
UPDATE users SET bubble_theme = 'solid_periwinkle' WHERE bubble_theme IS NULL;
UPDATE users SET bg_gradient_size = 3 WHERE bg_gradient_size IS NULL;






use chatdb;
-- 🎨 МИГРАЦИЯ: Background theme като id (заменя старата bg_gradient_size колона)
-- Ако вече си изпълнила/изпълнил предишната migration_themes.sql, тази замества
-- bg_gradient_size с нова колона background_theme (string id), защото вече
-- background-ите се избират чрез клик на swatch (като bubble темите), не чрез
-- брой цветове.

-- Премахваме старата колона, ако вече съществува от предишната миграция
ALTER TABLE users DROP COLUMN bg_gradient_size;
ALTER TABLE users ADD COLUMN background_theme VARCHAR(30) DEFAULT 'bg_ombre_3';
UPDATE users SET background_theme = 'bg_ombre_3' WHERE background_theme IS NULL;


use chatdb;
ALTER TABLE users ADD COLUMN avatar_id VARCHAR(20) DEFAULT NULL;
ALTER TABLE users ADD COLUMN show_online_status BOOLEAN DEFAULT TRUE;

CREATE TABLE blocked_users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    blocker VARCHAR(50) NOT NULL,
    blocked VARCHAR(50) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_block_pair UNIQUE (blocker, blocked),
    CONSTRAINT fk_blocker FOREIGN KEY (blocker) REFERENCES users(username),
    CONSTRAINT fk_blocked FOREIGN KEY (blocked) REFERENCES users(username)
);

CREATE INDEX idx_blocked_lookup ON blocked_users(blocked);

-- ──────────────────────────────────────────────────────────────────

USE chatdb;

-- blocked_users (имената съвпадат точно)
ALTER TABLE blocked_users DROP FOREIGN KEY fk_blocker;
ALTER TABLE blocked_users DROP FOREIGN KEY fk_blocked;
ALTER TABLE blocked_users
    ADD CONSTRAINT fk_blocker FOREIGN KEY (blocker) REFERENCES users(username)
        ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT fk_blocked FOREIGN KEY (blocked) REFERENCES users(username)
        ON UPDATE CASCADE ON DELETE CASCADE;

-- friendships — няма съществуващи FK-та, директно добавяме
ALTER TABLE friendships
    ADD CONSTRAINT fk_friend_user_a FOREIGN KEY (user_a) REFERENCES users(username)
        ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT fk_friend_user_b FOREIGN KEY (user_b) REFERENCES users(username)
        ON UPDATE CASCADE ON DELETE CASCADE;