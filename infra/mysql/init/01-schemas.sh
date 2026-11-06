#!/bin/bash
# Runs once on first MySQL start. One schema + one user per service, each granted ONLY on its own schema.
# A shell script (not .sql) so passwords come from the environment instead of being hard-coded.
set -euo pipefail

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOSQL
CREATE DATABASE IF NOT EXISTS user_db    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS event_db   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS booking_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS payment_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'user_svc'@'%'    IDENTIFIED BY '${USER_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'event_svc'@'%'   IDENTIFIED BY '${EVENT_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'booking_svc'@'%' IDENTIFIED BY '${BOOKING_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'payment_svc'@'%' IDENTIFIED BY '${PAYMENT_DB_PASSWORD}';

GRANT ALL PRIVILEGES ON user_db.*    TO 'user_svc'@'%';
GRANT ALL PRIVILEGES ON event_db.*   TO 'event_svc'@'%';
GRANT ALL PRIVILEGES ON booking_db.* TO 'booking_svc'@'%';
GRANT ALL PRIVILEGES ON payment_db.* TO 'payment_svc'@'%';
FLUSH PRIVILEGES;
EOSQL
