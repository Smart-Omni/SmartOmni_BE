-- TikTok Shop connection (simulator phase 03): OAuth-style authorization, shop identity,
-- encrypted token storage and single-use authorization state.
-- App key/secret belong to the SmartOmni application (backend configuration), never to a shop owner,
-- so tokens and shop identifiers are stored per connection but app credentials are not.

-- New lifecycle states. A new enum value must not be referenced in the same transaction that adds it,
-- so nothing below uses missing_scope / refresh_failed / revoked.
ALTER TYPE connection_status ADD VALUE IF NOT EXISTS 'missing_scope';
ALTER TYPE connection_status ADD VALUE IF NOT EXISTS 'refresh_failed';
ALTER TYPE connection_status ADD VALUE IF NOT EXISTS 'revoked';

ALTER TABLE marketplace_connections
  ADD COLUMN seller_open_id          varchar,
  ADD COLUMN shop_cipher             varchar,
  ADD COLUMN shop_region             varchar,
  ADD COLUMN seller_type             varchar,
  ADD COLUMN granted_scopes          varchar,
  ADD COLUMN access_token_encrypted  varchar,
  ADD COLUMN refresh_token_encrypted varchar,
  ADD COLUMN access_token_expires_at  timestamptz,
  ADD COLUMN refresh_token_expires_at timestamptz,
  ADD COLUMN token_version           bigint NOT NULL DEFAULT 0,
  ADD COLUMN last_error_code         varchar,
  ADD COLUMN last_error_at           timestamptz;

COMMENT ON COLUMN marketplace_connections.access_token_encrypted IS 'AES-GCM (random IV) ciphertext; never returned by any API';
COMMENT ON COLUMN marketplace_connections.refresh_token_encrypted IS 'AES-GCM (random IV) ciphertext; never returned by any API';
COMMENT ON COLUMN marketplace_connections.token_version IS 'Compare-and-swap counter incremented on every token rotation';

-- At most one TikTok connection row per tenant (reconnect and disconnect reuse the row).
-- The existing unique key (tenant_id, platform, shop_id) lets NULL shop_id rows through.
CREATE UNIQUE INDEX uq_marketplace_connections_tiktok_per_tenant
  ON marketplace_connections (tenant_id)
  WHERE platform = 'tiktok_shop';

-- A TikTok shop can be linked to one tenant at a time. Row level security hides other tenants'
-- rows, so only a global unique index can enforce this.
CREATE UNIQUE INDEX uq_marketplace_connections_tiktok_shop
  ON marketplace_connections (shop_id)
  WHERE platform = 'tiktok_shop' AND shop_id IS NOT NULL AND status <> 'disconnected';

-- Single-use, expiring authorization state. Only the SHA-256 hash is stored; the plain value
-- exists only in the authorization URL handed to the Tenant Admin's browser.
CREATE TABLE marketplace_authorization_states (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     bigint NOT NULL REFERENCES tenants(id),
  connection_id bigint NOT NULL,
  user_id       bigint NOT NULL REFERENCES users(id),
  state_hash    varchar(64) NOT NULL,
  expires_at    timestamptz NOT NULL,
  consumed_at   timestamptz,
  created_at    timestamptz DEFAULT now(),
  updated_at    timestamptz,
  CONSTRAINT uq_marketplace_authorization_states_hash UNIQUE (state_hash),
  CONSTRAINT fk_marketplace_authorization_states_connection FOREIGN KEY (tenant_id, connection_id)
    REFERENCES marketplace_connections (tenant_id, id)
);
CREATE INDEX idx_marketplace_authorization_states_tenant
  ON marketplace_authorization_states (tenant_id, expires_at);

ALTER TABLE marketplace_authorization_states ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_authorization_states FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON marketplace_authorization_states
  USING (tenant_id = app_current_tenant_id() OR app_is_super_admin())
  WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_super_admin());

GRANT SELECT, INSERT, UPDATE ON marketplace_authorization_states TO smartomni_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO smartomni_app;
