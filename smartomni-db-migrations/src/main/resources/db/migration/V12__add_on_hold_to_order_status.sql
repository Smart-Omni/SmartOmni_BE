-- Flyway migration V12: Add on_hold to order_status enum
ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'on_hold';
