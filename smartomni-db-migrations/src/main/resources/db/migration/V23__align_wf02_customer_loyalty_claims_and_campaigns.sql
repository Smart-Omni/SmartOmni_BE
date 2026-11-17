-- =========================================================
-- SMARTOMNI - V23__align_wf02_customer_loyalty_claims_and_campaigns.sql
-- WF-02 alignment:
--   1. Point all loyalty customer_id FKs to users(tenant_id, id) and drop customer_accounts.
--   2. Enforce customer role & no approved/completed returns in loyalty_check_claim_eligibility().
--   3. Allow admin/tenant_admin/staff and require CONNECTED shop in loyalty_check_reward_campaign().
--   4. Add loyalty_notification_events for post-issued notifications.
--   5. Remove temporary V22 cron-based loyalty tables/columns.
-- =========================================================

SET LOCAL app.user_role = 'super_admin';

-- 1. Allow composite FK (tenant_id, customer_id) -> users(tenant_id, id)
ALTER TABLE users
  ADD CONSTRAINT uq_users_tenant_id UNIQUE (tenant_id, id);

-- 2. Re-point loyalty FKs from customer_accounts to users(tenant_id, id)
ALTER TABLE loyalty_claims
  DROP CONSTRAINT IF EXISTS fk_loyalty_claims_customer,
  ADD CONSTRAINT fk_loyalty_claims_customer FOREIGN KEY (tenant_id, customer_id)
    REFERENCES users (tenant_id, id);

ALTER TABLE loyalty_point_accounts
  DROP CONSTRAINT IF EXISTS fk_loyalty_point_accounts_customer,
  ADD CONSTRAINT fk_loyalty_point_accounts_customer FOREIGN KEY (tenant_id, customer_id)
    REFERENCES users (tenant_id, id);

ALTER TABLE loyalty_voucher_issuances
  DROP CONSTRAINT IF EXISTS fk_loyalty_voucher_issuances_customer,
  ADD CONSTRAINT fk_loyalty_voucher_issuances_customer FOREIGN KEY (tenant_id, customer_id)
    REFERENCES users (tenant_id, id);

ALTER TABLE loyalty_point_ledger
  DROP CONSTRAINT IF EXISTS fk_loyalty_point_ledger_customer,
  ADD CONSTRAINT fk_loyalty_point_ledger_customer FOREIGN KEY (tenant_id, customer_id)
    REFERENCES users (tenant_id, id);

ALTER TABLE loyalty_reward_requests
  DROP CONSTRAINT IF EXISTS fk_reward_request_customer,
  ADD CONSTRAINT fk_reward_request_customer FOREIGN KEY (tenant_id, customer_id)
    REFERENCES users (tenant_id, id);

DROP TABLE IF EXISTS customer_accounts;

-- 3. Claim eligibility: completed order, >= 15 days, >= 1000 VND, no approved/completed return, active CUSTOMER user
CREATE OR REPLACE FUNCTION loyalty_check_claim_eligibility() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  order_row orders%ROWTYPE;
BEGIN
  SELECT * INTO order_row FROM orders
   WHERE tenant_id = NEW.tenant_id AND id = NEW.order_id FOR SHARE;
  IF NOT FOUND OR order_row.status IS DISTINCT FROM 'completed'
      OR order_row.eligible_for_points_at IS NULL
      OR order_row.eligible_for_points_at > now()
      OR order_row.total_amount IS NULL OR order_row.total_amount < 1000 THEN
    RAISE EXCEPTION 'Order is not eligible for loyalty points'
      USING ERRCODE = '23514';
  END IF;

  IF EXISTS (
    SELECT 1 FROM returns_refunds r
     WHERE r.tenant_id = NEW.tenant_id
       AND r.order_id = NEW.order_id
       AND r.status IN ('approved', 'completed')
  ) THEN
    RAISE EXCEPTION 'Order with approved return/refund is not eligible for loyalty points'
      USING ERRCODE = '23514';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM users u
     WHERE u.tenant_id = NEW.tenant_id
       AND u.id = NEW.customer_id
       AND u.role = 'customer'
       AND u.status = 'active'
  ) THEN
    RAISE EXCEPTION 'Loyalty claim customer must be an active CUSTOMER user in the same tenant'
      USING ERRCODE = '23514';
  END IF;

  NEW.eligible_amount_vnd := order_row.total_amount;
  NEW.points_awarded := floor(order_row.total_amount / 1000)::bigint;
  RETURN NEW;
END $$;

-- 4. Campaign creator & connected shop check (supports admin, tenant_admin, staff)
ALTER TABLE loyalty_reward_campaigns
  ADD COLUMN IF NOT EXISTS external_coupon_id varchar;

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
       AND u.role IN ('admin', 'tenant_admin', 'staff')
  ) THEN
    RAISE EXCEPTION 'Reward campaign creator must be tenant admin or staff'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

-- 5. Notification events recorded after voucher issuance
CREATE TABLE loyalty_notification_events (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id         bigint NOT NULL REFERENCES tenants(id),
  reward_request_id bigint NOT NULL,
  customer_id       bigint NOT NULL,
  event_type        varchar NOT NULL DEFAULT 'LOYALTY_VOUCHER_ISSUED',
  status            varchar NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'sent', 'failed')),
  payload           jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz NOT NULL DEFAULT now(),
  sent_at           timestamptz,
  CONSTRAINT uq_loyalty_notification_request UNIQUE (tenant_id, reward_request_id, event_type),
  CONSTRAINT fk_loyalty_notification_request FOREIGN KEY (tenant_id, reward_request_id)
    REFERENCES loyalty_reward_requests (tenant_id, id),
  CONSTRAINT fk_loyalty_notification_customer FOREIGN KEY (tenant_id, customer_id)
    REFERENCES users (tenant_id, id)
);

CREATE INDEX idx_loyalty_notification_events_status
  ON loyalty_notification_events (status, created_at);

ALTER TABLE loyalty_notification_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE loyalty_notification_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loyalty_notification_events
  USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
  WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());

GRANT SELECT, INSERT, UPDATE ON TABLE loyalty_notification_events TO smartomni_app;
GRANT UPDATE (status, code, external_voucher_id, failure_reason, issued_at, updated_at)
  ON loyalty_reward_requests TO smartomni_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO smartomni_app;

-- 6. Clean up temporary V22 cron-based loyalty artifacts
DROP TABLE IF EXISTS order_loyalty_ledger;
DROP TABLE IF EXISTS order_loyalty_balances;
DROP INDEX IF EXISTS idx_orders_loyalty_eligible;
ALTER TABLE orders
  DROP COLUMN IF EXISTS points_awarded_at,
  DROP COLUMN IF EXISTS points_awarded;
