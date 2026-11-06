-- Emails are already stripped + lower-cased by the application, so compare them byte-for-byte.
-- The schema default (utf8mb4_0900_ai_ci) is accent-insensitive: 'björn@x' would collide with 'bjorn@x'.
ALTER TABLE users MODIFY email VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
