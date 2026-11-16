-- =========================================================
-- SMARTOMNI - V20__shopee_return_inspection_and_inventory_idempotency.sql
-- 1. Extend returns_refunds with connection_id, external_return_id, platform, return_type, refund_amount.
-- 2. Add unique index on (tenant_id, connection_id, external_return_id) for idempotent return syncing.
-- 3. Create return_refund_items table for itemized return lines and inspection quantities.
-- 4. Create inventory_restock_logs table for idempotent stock crediting.
-- 5. Enable & force PostgreSQL RLS with tenant isolation.
-- =========================================================

-- 1. Extend returns_refunds
ALTER TABLE returns_refunds
    ADD COLUMN IF NOT EXISTS connection_id bigint,
    ADD COLUMN IF NOT EXISTS external_return_id varchar(100),
    ADD COLUMN IF NOT EXISTS platform platform_type,
    ADD COLUMN IF NOT EXISTS return_type varchar(50),
    ADD COLUMN IF NOT EXISTS refund_amount numeric(14,2);

CREATE UNIQUE INDEX IF NOT EXISTS uq_returns_refunds_tenant_conn_ext
    ON returns_refunds (tenant_id, connection_id, external_return_id)
    WHERE connection_id IS NOT NULL AND external_return_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_returns_refunds_connection_id
    ON returns_refunds (connection_id);

-- 2. Create return_refund_items
CREATE TABLE IF NOT EXISTS return_refund_items (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           bigint NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    return_refund_id    bigint NOT NULL REFERENCES returns_refunds(id) ON DELETE CASCADE,
    sku_id              bigint,
    external_item_id    varchar(100),
    item_name           varchar(255),
    quantity_returned   int NOT NULL DEFAULT 1,
    quantity_inspected  int DEFAULT 0,
    refund_amount       numeric(14,2),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz
);

CREATE INDEX IF NOT EXISTS idx_return_refund_items_return_id ON return_refund_items (return_refund_id);
CREATE INDEX IF NOT EXISTS idx_return_refund_items_tenant_sku ON return_refund_items (tenant_id, sku_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE return_refund_items TO smartomni_app;
GRANT USAGE, SELECT ON SEQUENCE return_refund_items_id_seq TO smartomni_app;

ALTER TABLE return_refund_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE return_refund_items FORCE ROW LEVEL SECURITY;

CREATE POLICY return_refund_items_tenant_isolation ON return_refund_items
    USING (app_is_super_admin() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_is_super_admin() OR tenant_id = app_current_tenant_id());

-- 3. Create inventory_restock_logs
CREATE TABLE IF NOT EXISTS inventory_restock_logs (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           bigint NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sku_id              bigint NOT NULL,
    quantity            int NOT NULL,
    idempotency_key     varchar(255) NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_inventory_restock_tenant_key
    ON inventory_restock_logs (tenant_id, idempotency_key);

CREATE INDEX IF NOT EXISTS idx_inventory_restock_sku
    ON inventory_restock_logs (tenant_id, sku_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE inventory_restock_logs TO smartomni_app;
GRANT USAGE, SELECT ON SEQUENCE inventory_restock_logs_id_seq TO smartomni_app;

ALTER TABLE inventory_restock_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_restock_logs FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_restock_logs_tenant_isolation ON inventory_restock_logs
    USING (app_is_super_admin() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_is_super_admin() OR tenant_id = app_current_tenant_id());
