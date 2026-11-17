-- WF-02: TikTok Shop voucher issuance history and automated 15-day order loyalty awards.

CREATE TABLE tiktok_vouchers (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id           bigint NOT NULL REFERENCES tenants(id),
  connection_id       bigint NOT NULL,
  shop_id             varchar,
  tiktok_voucher_id   varchar NOT NULL,
  voucher_code        varchar,
  title               varchar NOT NULL,
  discount_type       varchar NOT NULL CHECK (discount_type IN ('PERCENT_OFF', 'AMOUNT_OFF')),
  discount_value      numeric(14,2) NOT NULL CHECK (discount_value > 0),
  max_discount_amount numeric(14,2),
  min_order_amount    numeric(14,2),
  usage_limit         integer,
  redeemed_count      integer NOT NULL DEFAULT 0,
  start_time          timestamptz NOT NULL,
  end_time            timestamptz NOT NULL,
  status              varchar NOT NULL DEFAULT 'ONGOING',
  idempotency_key     varchar(128),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz,
  CONSTRAINT fk_tiktok_vouchers_connection FOREIGN KEY (tenant_id, connection_id)
    REFERENCES marketplace_connections (tenant_id, id),
  CONSTRAINT uq_tiktok_vouchers_conn_external UNIQUE (connection_id, tiktok_voucher_id)
);

CREATE INDEX idx_tiktok_vouchers_tenant
  ON tiktok_vouchers (tenant_id, created_at DESC);

ALTER TABLE tiktok_vouchers ENABLE ROW LEVEL SECURITY;
ALTER TABLE tiktok_vouchers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tiktok_vouchers
  USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
  WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE tiktok_vouchers TO smartomni_app;

-- Track automated loyalty points awarded per order after the 15-day return window.
ALTER TABLE orders
  ADD COLUMN points_awarded_at timestamptz,
  ADD COLUMN points_awarded bigint;

CREATE INDEX idx_orders_loyalty_eligible
  ON orders (tenant_id, eligible_for_points_at)
  WHERE status = 'completed' AND points_awarded_at IS NULL AND eligible_for_points_at IS NOT NULL;

CREATE TABLE order_loyalty_ledger (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id           bigint NOT NULL REFERENCES tenants(id),
  order_id            bigint NOT NULL,
  customer_key        varchar NOT NULL,
  points_awarded      bigint NOT NULL CHECK (points_awarded > 0),
  eligible_amount_vnd numeric(14,2) NOT NULL,
  idempotency_key     varchar NOT NULL,
  awarded_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_order_loyalty_ledger_idempotency UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT fk_order_loyalty_ledger_order FOREIGN KEY (tenant_id, order_id)
    REFERENCES orders (tenant_id, id)
);

CREATE INDEX idx_order_loyalty_ledger_customer
  ON order_loyalty_ledger (tenant_id, customer_key, awarded_at DESC);

CREATE TABLE order_loyalty_balances (
  tenant_id    bigint NOT NULL REFERENCES tenants(id),
  customer_key varchar NOT NULL,
  total_points bigint NOT NULL DEFAULT 0,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, customer_key)
);

ALTER TABLE order_loyalty_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_loyalty_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_loyalty_ledger
  USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
  WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());

ALTER TABLE order_loyalty_balances ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_loyalty_balances FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_loyalty_balances
  USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
  WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());

GRANT SELECT, INSERT, UPDATE ON TABLE order_loyalty_ledger TO smartomni_app;
GRANT SELECT, INSERT, UPDATE ON TABLE order_loyalty_balances TO smartomni_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO smartomni_app;
