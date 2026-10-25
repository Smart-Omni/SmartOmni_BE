-- TikTok Shop catalog mapping (simulator phase 04): marketplace product/SKU identity on links, warehouses,
-- import run reports and price history context. Platform IDs are strings owned by the platform; they never
-- replace SmartOmni's own ids.

-- A link is the mapping "connection + platform SKU id -> SmartOmni SKU".
ALTER TABLE product_marketplace_links
  ADD COLUMN external_product_id varchar,
  ADD COLUMN external_sku_id     varchar,
  ADD COLUMN seller_sku          varchar,
  ADD COLUMN currency            varchar(3),
  ADD COLUMN external_status     varchar;

COMMENT ON COLUMN product_marketplace_links.external_sku_id IS 'Platform SKU id (string, owned by the platform). Unique per connection.';
COMMENT ON COLUMN product_marketplace_links.seller_sku IS 'Seller SKU as the platform reports it; matched to product_skus.sku_code on import.';

-- connection_id is globally unique (identity), so the same platform SKU id under another connection never collides.
CREATE UNIQUE INDEX uq_product_marketplace_links_external_sku
  ON product_marketplace_links (connection_id, external_sku_id)
  WHERE external_sku_id IS NOT NULL;
CREATE INDEX idx_product_marketplace_links_external_product
  ON product_marketplace_links (connection_id, external_product_id)
  WHERE external_product_id IS NOT NULL;

-- A link may only point at a connection of its own tenant. NOT VALID keeps legacy rows untouched; new rows are checked.
ALTER TABLE product_marketplace_links
  ADD CONSTRAINT fk_product_marketplace_links_tenant_connection
  FOREIGN KEY (tenant_id, connection_id) REFERENCES marketplace_connections (tenant_id, id) NOT VALID;

-- Price history: keep which connection / platform SKU a change belongs to and whether it was applied.
-- An anomaly (FR-092) is recorded with applied = false and waits for a Manager; the link price is untouched.
ALTER TABLE price_sync_history
  ADD COLUMN connection_id    bigint,
  ADD COLUMN external_sku_id  varchar,
  ADD COLUMN currency         varchar(3),
  ADD COLUMN applied          boolean NOT NULL DEFAULT true;
CREATE INDEX idx_price_sync_history_sku
  ON price_sync_history (tenant_id, sku_id, platform, changed_at DESC);

-- Warehouses reported by the platform (Get Warehouse List).
CREATE TABLE marketplace_warehouses (
  id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id             bigint NOT NULL REFERENCES tenants(id),
  connection_id         bigint NOT NULL,
  external_warehouse_id varchar NOT NULL,
  name                  varchar,
  warehouse_type        varchar,
  sub_type              varchar,
  effect_status         varchar,
  is_default            boolean NOT NULL DEFAULT false,
  city                  varchar,
  region_code           varchar,
  synced_at             timestamptz NOT NULL DEFAULT now(),
  created_at            timestamptz DEFAULT now(),
  updated_at            timestamptz,
  CONSTRAINT fk_marketplace_warehouses_connection FOREIGN KEY (tenant_id, connection_id)
    REFERENCES marketplace_connections (tenant_id, id),
  CONSTRAINT uq_marketplace_warehouses UNIQUE (connection_id, external_warehouse_id)
);

-- One row per product import run. `items` holds the per-product outcomes that were not a plain success.
CREATE TABLE marketplace_import_runs (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id           bigint NOT NULL REFERENCES tenants(id),
  connection_id       bigint NOT NULL,
  status              varchar NOT NULL,
  started_at          timestamptz NOT NULL DEFAULT now(),
  finished_at         timestamptz,
  pages               integer NOT NULL DEFAULT 0,
  products_seen       integer NOT NULL DEFAULT 0,
  products_created    integer NOT NULL DEFAULT 0,
  products_updated    integer NOT NULL DEFAULT 0,
  products_skipped    integer NOT NULL DEFAULT 0,
  products_failed     integer NOT NULL DEFAULT 0,
  skus_created        integer NOT NULL DEFAULT 0,
  skus_linked         integer NOT NULL DEFAULT 0,
  skus_conflicted     integer NOT NULL DEFAULT 0,
  skus_unmatched      integer NOT NULL DEFAULT 0,
  sku_limit           integer,
  sku_limit_reached   boolean NOT NULL DEFAULT false,
  error_message       varchar,
  items               jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at          timestamptz DEFAULT now(),
  updated_at          timestamptz,
  CONSTRAINT fk_marketplace_import_runs_connection FOREIGN KEY (tenant_id, connection_id)
    REFERENCES marketplace_connections (tenant_id, id),
  CONSTRAINT ck_marketplace_import_runs_status CHECK (status IN ('running', 'completed', 'completed_with_errors', 'stopped_sku_limit', 'failed'))
);
-- At most one running import per connection (blocks concurrent duplicate imports).
CREATE UNIQUE INDEX uq_marketplace_import_runs_running
  ON marketplace_import_runs (connection_id) WHERE status = 'running';
CREATE INDEX idx_marketplace_import_runs_connection
  ON marketplace_import_runs (tenant_id, connection_id, started_at DESC);

ALTER TABLE marketplace_warehouses ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_warehouses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON marketplace_warehouses
  USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
  WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());

ALTER TABLE marketplace_import_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_import_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON marketplace_import_runs
  USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
  WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());

GRANT SELECT, INSERT, UPDATE, DELETE ON marketplace_warehouses TO smartomni_app;
GRANT SELECT, INSERT, UPDATE ON marketplace_import_runs TO smartomni_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO smartomni_app;
