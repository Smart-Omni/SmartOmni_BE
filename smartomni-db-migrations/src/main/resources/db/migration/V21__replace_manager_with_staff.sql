-- =========================================================
-- SMARTOMNI - V21__replace_manager_with_staff.sql
-- B9: remove the MANAGER role. Existing manager data becomes staff:
--   users.role                      manager -> staff
--   staff_invitations.role          MANAGER -> STAFF
--   order_status_history.source     manager -> staff
-- user_role and order_change_source are recreated without the manager label.
-- =========================================================

-- Business tables are FORCE ROW LEVEL SECURITY; the migration owner is not exempt.
SET LOCAL app.user_role = 'super_admin';

-- 1. users / staff_invitations ------------------------------------------------
UPDATE users SET role = 'staff' WHERE role = 'manager';

UPDATE staff_invitations SET role = 'STAFF' WHERE role = 'MANAGER';
ALTER TABLE staff_invitations DROP CONSTRAINT chk_staff_invitations_role;
ALTER TABLE staff_invitations ADD CONSTRAINT chk_staff_invitations_role CHECK (role IN ('STAFF'));

ALTER TYPE user_role RENAME TO user_role_old;
CREATE TYPE user_role AS ENUM ('super_admin', 'admin', 'tenant_admin', 'customer', 'staff');
ALTER TABLE users ALTER COLUMN role TYPE user_role USING role::text::user_role;
DROP TYPE user_role_old;

-- 2. order_status_history.source ---------------------------------------------
-- The old enum has no 'staff' label, so the value is mapped while the type changes.
ALTER TYPE order_change_source RENAME TO order_change_source_old;
CREATE TYPE order_change_source AS ENUM ('staff', 'marketplace_webhook', 'system');
ALTER TABLE order_status_history ALTER COLUMN source TYPE order_change_source
  USING (CASE WHEN source::text = 'manager' THEN 'staff' ELSE source::text END)::order_change_source;
DROP TYPE order_change_source_old;
COMMENT ON COLUMN order_status_history.source IS
  'staff | marketplace_webhook | system - chặn nhân viên sửa đè trạng thái do sàn tự động đẩy';

-- 3. Reward-campaign creator check (body from V8, 'manager' -> 'staff') --------
CREATE OR REPLACE FUNCTION loyalty_check_reward_campaign() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE shop_platform platform_type;
BEGIN
  SELECT platform INTO shop_platform FROM marketplace_connections
   WHERE tenant_id = NEW.tenant_id AND id = NEW.connection_id;
  IF shop_platform IS NULL OR shop_platform NOT IN ('shopee', 'tiktok_shop') THEN
    RAISE EXCEPTION 'Reward campaign requires a Shopee or TikTok shop'
      USING ERRCODE = '23514';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM users WHERE id = NEW.created_by
      AND tenant_id = NEW.tenant_id AND role IN ('admin', 'staff')) THEN
    RAISE EXCEPTION 'Reward campaign creator must be tenant staff'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
