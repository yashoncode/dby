-- Test data shared by MySQL 8.4 and MariaDB 11.4. Every table here backs a test in core/tests.
USE shop;
-- MariaDB's init client does not default to utf8mb4; the emoji row needs it.
SET NAMES utf8mb4;

CREATE TABLE digits (d INT PRIMARY KEY);
INSERT INTO digits VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9);

-- 100,000 rows; every 10th row has a 5000-character note.
CREATE TABLE orders (
  id BIGINT UNSIGNED PRIMARY KEY,
  customer VARCHAR(80) NOT NULL,
  status VARCHAR(16) NOT NULL,
  total DECIMAL(12, 2) NOT NULL,
  notes TEXT NULL,
  created_at DATETIME NOT NULL
);
INSERT INTO orders (id, customer, status, total, notes, created_at)
SELECT n,
       CONCAT('Customer ', n % 997),
       ELT(1 + n % 4, 'shipped', 'pending', 'delivered', 'cancelled'),
       (n % 100000) / 100,
       IF(n % 10 = 0, REPEAT('x', 5000), NULL),
       DATE_ADD('2026-01-01', INTERVAL n MINUTE)
FROM (SELECT a.d + 10 * b.d + 100 * c.d + 1000 * e.d + 10000 * f.d + 1 AS n
      FROM digits a, digits b, digits c, digits e, digits f) seq;

-- Text key under a case-insensitive collation, with non-ASCII and emoji values.
CREATE TABLE tags (
  name VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci PRIMARY KEY,
  note VARCHAR(20)
);
INSERT INTO tags (name, note) VALUES
  ('apple', ''), ('Banana', ''), ('cherry', ''), ('Äpfel', ''), ('zebra', ''),
  ('ünder', ''), ('😀smile', ''), ('mango', ''), ('kiwi', ''), ('Date', '');

-- Composite key, 100 rows.
CREATE TABLE pairs (a INT NOT NULL, b INT NOT NULL, v VARCHAR(10), PRIMARY KEY (a, b));
INSERT INTO pairs SELECT x.d, y.d, CONCAT(x.d, '-', y.d) FROM digits x, digits y;

-- No primary key, 100 rows.
CREATE TABLE nopk (v INT);
INSERT INTO nopk SELECT a.d + 10 * b.d FROM digits a, digits b;

-- Values that break naive clients. sql_mode is cleared so the zero date is accepted.
SET SESSION sql_mode = '';
CREATE TABLE exact_values (
  id INT PRIMARY KEY,
  big BIGINT UNSIGNED,
  signed_big BIGINT,
  money DECIMAL(65, 30),
  bin VARBINARY(16),
  blobby LONGBLOB,
  zero_date DATETIME
);
INSERT INTO exact_values VALUES (
  1,
  18446744073709551615,
  -9223372036854775808,
  12345678901234567890123456789012345.123456789012345678901234567890,
  X'00ff',
  REPEAT(X'ab', 1000),
  '0000-00-00 00:00:00'
);
