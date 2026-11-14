-- =========================================================
-- SMARTOMNI - V19__create_staff_invitations.sql
-- 1. Add 'staff' role to user_role enum.
-- 2. Create staff_invitations table for Staff Onboarding via Invitation.
-- 3. Enable & force PostgreSQL RLS with tenant isolation and invitee self-access.
-- =========================================================

ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'staff';

CREATE TABLE staff_invitations (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       bigint NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    invited_email   varchar(255) NOT NULL,
    invited_user_id bigint REFERENCES users(id) ON DELETE SET NULL,
    role            varchar(50) NOT NULL DEFAULT 'STAFF',
    status          varchar(30) NOT NULL DEFAULT 'PENDING',
    invited_by      bigint NOT NULL REFERENCES users(id),
    expires_at      timestamptz NOT NULL,
    responded_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz,
    CONSTRAINT chk_staff_invitations_role CHECK (role IN ('STAFF', 'MANAGER')),
    CONSTRAINT chk_staff_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX idx_staff_invitations_tenant_status ON staff_invitations (tenant_id, status);
CREATE INDEX idx_staff_invitations_email_status ON staff_invitations (lower(invited_email), status);
CREATE INDEX idx_staff_invitations_user_status ON staff_invitations (invited_user_id, status);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE staff_invitations TO smartomni_app;
GRANT USAGE, SELECT ON SEQUENCE staff_invitations_id_seq TO smartomni_app;

ALTER TABLE staff_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_invitations FORCE ROW LEVEL SECURITY;

CREATE POLICY staff_invitations_tenant_isolation ON staff_invitations
    USING (app_is_super_admin() OR tenant_id = app_current_tenant_id())
    WITH CHECK (app_is_super_admin() OR tenant_id = app_current_tenant_id());

CREATE POLICY staff_invitations_invitee_select ON staff_invitations
    FOR SELECT
    USING (
        invited_user_id = NULLIF(current_setting('app.auth_user_id', true), '')::bigint
        OR lower(invited_email) = lower(NULLIF(current_setting('app.auth_email', true), ''))
    );

CREATE POLICY staff_invitations_invitee_update ON staff_invitations
    FOR UPDATE
    USING (
        invited_user_id = NULLIF(current_setting('app.auth_user_id', true), '')::bigint
        OR lower(invited_email) = lower(NULLIF(current_setting('app.auth_email', true), ''))
    )
    WITH CHECK (
        invited_user_id = NULLIF(current_setting('app.auth_user_id', true), '')::bigint
        OR lower(invited_email) = lower(NULLIF(current_setting('app.auth_email', true), ''))
    );
