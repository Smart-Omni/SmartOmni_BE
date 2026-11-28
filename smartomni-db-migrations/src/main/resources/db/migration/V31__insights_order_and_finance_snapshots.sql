-- Insights support: marketplace order/item/return price components, timestamps, and normalized finance snapshots.

ALTER TABLE orders
    ADD COLUMN marketplace_created_at timestamptz,
    ADD COLUMN marketplace_cancelled_at timestamptz,
    ADD COLUMN original_product_price numeric(14, 2),
    ADD COLUMN seller_discount numeric(14, 2),
    ADD COLUMN platform_discount numeric(14, 2),
    ADD COLUMN merchandise_value numeric(14, 2),
    ADD COLUMN buyer_paid_shipping_fee numeric(14, 2),
    ADD COLUMN shipping_fee_seller_discount numeric(14, 2),
    ADD COLUMN tax_amount numeric(14, 2);

ALTER TABLE order_items
    ADD COLUMN external_sku_id varchar(128),
    ADD COLUMN seller_sku varchar(128),
    ADD COLUMN product_name varchar(512),
    ADD COLUMN original_price numeric(14, 2),
    ADD COLUMN seller_discount numeric(14, 2),
    ADD COLUMN platform_discount numeric(14, 2),
    ADD COLUMN merchandise_value numeric(14, 2);

ALTER TABLE returns_refunds
    ADD COLUMN refund_subtotal numeric(14, 2),
    ADD COLUMN refund_shipping_fee numeric(14, 2),
    ADD COLUMN reverse_shipping_fee numeric(14, 2),
    ADD COLUMN marketplace_created_at timestamptz;

CREATE TABLE order_finance_snapshots (
    id                         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                  bigint NOT NULL REFERENCES tenants(id),
    connection_id              bigint,
    platform                   platform_type NOT NULL,
    platform_order_id          varchar NOT NULL,
    order_id                   bigint REFERENCES orders(id),
    currency                   varchar(16) NOT NULL DEFAULT 'VND',
    settlement_status          varchar(32) NOT NULL DEFAULT 'ESTIMATED',
    estimated                  boolean NOT NULL DEFAULT false,
    statement_id               varchar(128),
    statement_time             timestamptz,
    buyer_payment              numeric(14, 2),
    merchandise_value          numeric(14, 2),
    commission_fee             numeric(14, 2),
    service_fee                numeric(14, 2),
    transaction_fee            numeric(14, 2),
    tax_amount                 numeric(14, 2),
    platform_fees              numeric(14, 2),
    actual_shipping_fee        numeric(14, 2),
    buyer_paid_shipping_fee    numeric(14, 2),
    platform_shipping_rebate   numeric(14, 2),
    seller_shipping_discount   numeric(14, 2),
    seller_shipping_cost       numeric(14, 2),
    seller_return_refund       numeric(14, 2),
    reverse_shipping_fee       numeric(14, 2),
    other_adjustments          numeric(14, 2),
    seller_settlement          numeric(14, 2),
    source_endpoint            varchar(255),
    source_watermark           timestamptz,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    updated_at                 timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_order_finance_snapshots UNIQUE (tenant_id, platform, platform_order_id)
);

CREATE INDEX idx_order_finance_snapshots_tenant_platform ON order_finance_snapshots (tenant_id, platform, statement_time);

GRANT SELECT, INSERT, UPDATE, DELETE ON order_finance_snapshots TO smartomni_app;

ALTER TABLE order_finance_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_finance_snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_finance_snapshots
    USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
    WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());
