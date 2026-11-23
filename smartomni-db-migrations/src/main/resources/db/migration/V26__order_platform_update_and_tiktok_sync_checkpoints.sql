-- Marketplace order freshness and reconciliation (TikTok simulator alignment, phase 1).

-- Platform update_time of the last applied status. Older webhook/polling events are ignored so a
-- reordered delivery cannot move an order back. NULL for orders whose platform sends no update time (Shopee).
ALTER TABLE orders ADD COLUMN platform_updated_at timestamptz;

-- Why an order needs manual reconciliation, e.g. SKU_UNMAPPED when a line item has no SmartOmni SKU.
ALTER TABLE orders ADD COLUMN reconciliation_reason varchar(64);

COMMENT ON COLUMN orders.platform_updated_at IS 'update_time của sàn cho trạng thái đã áp dụng; event cũ hơn bị bỏ qua';
COMMENT ON COLUMN orders.reconciliation_reason IS 'Lý do cần đối soát thủ công, ví dụ SKU_UNMAPPED';

-- Incremental polling checkpoint per connection and resource (ORDERS, RETURNS, CANCELLATIONS).
CREATE TABLE tiktok_sync_checkpoints (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES tenants(id),
    connection_id bigint NOT NULL REFERENCES marketplace_connections(id),
    resource varchar(32) NOT NULL,
    last_update_time bigint NOT NULL CHECK (last_update_time >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tiktok_sync_checkpoint UNIQUE (connection_id, resource)
);

COMMENT ON TABLE tiktok_sync_checkpoints IS 'Mốc update_time (epoch giây, không gồm) đã polling xong cho từng kết nối và tài nguyên';

GRANT SELECT, INSERT, UPDATE, DELETE ON tiktok_sync_checkpoints TO smartomni_app;

ALTER TABLE tiktok_sync_checkpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE tiktok_sync_checkpoints FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tiktok_sync_checkpoints
    USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
    WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());
