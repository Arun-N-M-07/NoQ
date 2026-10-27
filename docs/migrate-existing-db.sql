-- One-off clean-up for a MySQL database created by an OLDER version of NoQ.
-- Hibernate's ddl-auto=update never drops tables or columns, so removing a field from Java leaves it in the database.
-- A fresh database does not need this file. Run it ONCE, after taking a backup:
--   mysql -u root -p noq_db < docs/migrate-existing-db.sql
-- (MySQL 8 has no DROP COLUMN IF EXISTS; running it a second time fails harmlessly at the first missing column.)

-- Payment tracking was removed: payment is taken by hand at the counter and NoQ does not record it.
-- Without this, new orders cannot be saved: payment_method / payment_status are NOT NULL and Hibernate no longer fills them.
DROP TABLE IF EXISTS payment;
ALTER TABLE customer_order DROP COLUMN payment_method, DROP COLUMN payment_status;

-- Stock tracking was removed: the kitchen's "available" switch is the only control.
ALTER TABLE menu_item DROP COLUMN available_quantity;
