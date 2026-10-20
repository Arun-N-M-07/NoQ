-- NoQ database schema, dumped from a real MySQL 8.4.9 server after Hibernate created it
-- from the @Entity classes (spring.jpa.hibernate.ddl-auto=update), so it matches the Java code.
-- Enum fields are plain VARCHAR(20) (columnDefinition), not MySQL ENUM or CHECK constraints, so adding
-- an enum value in Java never needs an ALTER TABLE.
-- The application creates these tables itself; this file is for the report and for manual setup:
--   mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS noq_db"
--   mysql -u root -p noq_db < docs/schema-mysql.sql
--   mysql -u root -p noq_db < noq-app/src/main/resources/data.sql

CREATE TABLE `customer_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `idempotency_key` varchar(64) DEFAULT NULL,
  `order_reference` varchar(20) NOT NULL,
  `payment_method` varchar(20) NOT NULL,
  `payment_status` varchar(20) NOT NULL,
  `pickup_otp_hash` varchar(100) NOT NULL,
  `status` varchar(20) NOT NULL,
  `subtotal` decimal(10,2) NOT NULL,
  `total_amount` decimal(10,2) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKtin0qiwd1i442btjqjdidiw1` (`order_reference`),
  UNIQUE KEY `UK6lspvofkvjdcw7ts1db24yeug` (`idempotency_key`),
  KEY `idx_order_status_created` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `menu_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `available` bit(1) NOT NULL,
  `available_quantity` int NOT NULL,
  `category` varchar(20) NOT NULL,
  `description` varchar(300) DEFAULT NULL,
  `image_url` varchar(300) DEFAULT NULL,
  `name` varchar(80) NOT NULL,
  `popularity_count` int NOT NULL,
  `price` decimal(8,2) NOT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_menu_item_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `item_name_snapshot` varchar(80) NOT NULL,
  `line_total` decimal(10,2) NOT NULL,
  `quantity` int NOT NULL,
  `unit_price_snapshot` decimal(8,2) NOT NULL,
  `order_id` bigint NOT NULL,
  `menu_item_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKgv4bnmo7cbib2nh0b2rw9yvir` (`order_id`),
  KEY `FK1my78itket7mhyn3wt4g6j4sq` (`menu_item_id`),
  CONSTRAINT `FK1my78itket7mhyn3wt4g6j4sq` FOREIGN KEY (`menu_item_id`) REFERENCES `menu_item` (`id`),
  CONSTRAINT `FKgv4bnmo7cbib2nh0b2rw9yvir` FOREIGN KEY (`order_id`) REFERENCES `customer_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `payment` (
  `payment_type` varchar(20) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` decimal(10,2) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `status` varchar(20) NOT NULL,
  `received_by` varchar(50) DEFAULT NULL,
  `gateway` varchar(30) DEFAULT NULL,
  `transaction_reference` varchar(40) DEFAULT NULL,
  `order_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKjb82tm1s8qg4mx6eqog4fsctj` (`order_id`),
  CONSTRAINT `FKjb82tm1s8qg4mx6eqog4fsctj` FOREIGN KEY (`order_id`) REFERENCES `customer_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `staff_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `password_hash` varchar(100) NOT NULL,
  `role` varchar(20) NOT NULL,
  `username` varchar(50) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKl5f8rn52rcqittbblcvouw8u9` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
