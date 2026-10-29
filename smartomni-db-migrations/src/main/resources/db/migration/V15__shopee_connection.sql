-- Shopee connection: uniqueness constraints for one Shopee shop per tenant
-- and one tenant per Shopee shop across the platform.

-- At most one Shopee connection row per tenant (reconnect and disconnect reuse the row).
CREATE UNIQUE INDEX uq_marketplace_connections_shopee_per_tenant
  ON marketplace_connections (tenant_id)
  WHERE platform = 'shopee';

-- A Shopee shop can be linked to one tenant at a time. Row level security hides other tenants'
-- rows, so only a global unique index can enforce this.
CREATE UNIQUE INDEX uq_marketplace_connections_shopee_shop
  ON marketplace_connections (shop_id)
  WHERE platform = 'shopee' AND shop_id IS NOT NULL AND status <> 'disconnected';

