-- =========================================================
-- SMARTOMNI - V29__rename_staff_role_to_manager.sql
-- Rename the tenant operator role STAFF -> MANAGER.
--   user_role enum label        staff -> manager (existing users keep their rows)
--   staff_invitations.role      STAFF -> MANAGER
-- order_change_source keeps its 'staff' label: it describes the origin of a
-- status change, not a user role.
-- =========================================================

SET LOCAL app.user_role = 'super_admin';

-- 1. user_role ----------------------------------------------------------------
ALTER TYPE user_role RENAME VALUE 'staff' TO 'manager';

-- 2. staff_invitations.role ---------------------------------------------------
ALTER TABLE staff_invitations DROP CONSTRAINT chk_staff_invitations_role;
UPDATE staff_invitations SET role = 'MANAGER' WHERE role = 'STAFF';
ALTER TABLE staff_invitations ALTER COLUMN role SET DEFAULT 'MANAGER';
ALTER TABLE staff_invitations ADD CONSTRAINT chk_staff_invitations_role CHECK (role IN ('MANAGER'));

-- 3. Reward-campaign creator check (body from V23, 'staff' -> 'manager') ---------
CREATE OR REPLACE FUNCTION loyalty_check_reward_campaign() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  shop_platform platform_type;
  shop_status connection_status;
BEGIN
  SELECT platform, status INTO shop_platform, shop_status
    FROM marketplace_connections
   WHERE tenant_id = NEW.tenant_id AND id = NEW.connection_id;
  IF shop_platform IS NULL
      OR shop_platform NOT IN ('shopee', 'tiktok_shop')
      OR shop_status IS DISTINCT FROM 'connected' THEN
    RAISE EXCEPTION 'Reward campaign requires a CONNECTED Shopee or TikTok shop'
      USING ERRCODE = '23514';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM users u
     WHERE u.id = NEW.created_by
       AND u.tenant_id = NEW.tenant_id
       AND u.role IN ('admin', 'tenant_admin', 'manager')
  ) THEN
    RAISE EXCEPTION 'Reward campaign creator must be tenant admin or manager'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
