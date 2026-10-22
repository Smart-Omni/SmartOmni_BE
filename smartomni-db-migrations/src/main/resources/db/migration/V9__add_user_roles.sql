-- Flyway migration V9: Add tenant_admin and customer roles to user_role enum
ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'tenant_admin';
ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'customer';