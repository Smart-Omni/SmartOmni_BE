CREATE TABLE tiktok_inventory_webhook_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES tenants(id),
    connection_id bigint NOT NULL REFERENCES marketplace_connections(id),
    event_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tiktok_inventory_webhook_event UNIQUE (tenant_id, connection_id, event_id)
);

CREATE TABLE tiktok_inventory_warehouse_snapshots (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES tenants(id),
    connection_id bigint NOT NULL REFERENCES marketplace_connections(id),
    sku_id bigint NOT NULL REFERENCES product_skus(id),
    external_warehouse_id text NOT NULL,
    available_quantity integer NOT NULL CHECK (available_quantity >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tiktok_inventory_warehouse UNIQUE (connection_id, sku_id, external_warehouse_id)
);

CREATE TABLE tiktok_inventory_sku_snapshots (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES tenants(id),
    connection_id bigint NOT NULL REFERENCES marketplace_connections(id),
    sku_id bigint NOT NULL REFERENCES product_skus(id),
    available_quantity integer NOT NULL CHECK (available_quantity >= 0),
    observed_at timestamptz NOT NULL,
    CONSTRAINT uq_tiktok_inventory_sku_snapshot UNIQUE (connection_id, sku_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON tiktok_inventory_webhook_events,
    tiktok_inventory_warehouse_snapshots, tiktok_inventory_sku_snapshots TO smartomni_app;

ALTER TABLE tiktok_inventory_webhook_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE tiktok_inventory_webhook_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tiktok_inventory_webhook_events
    USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
    WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());

ALTER TABLE tiktok_inventory_warehouse_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE tiktok_inventory_warehouse_snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tiktok_inventory_warehouse_snapshots
    USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
    WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());

ALTER TABLE tiktok_inventory_sku_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE tiktok_inventory_sku_snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tiktok_inventory_sku_snapshots
    USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
    WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());
