package com.smartomni.integration.service.tiktok;

/** Told once when a connection needs the Tenant Admin to authorize again. */
public interface ReconnectNotifier {

    /** @param reasonCode machine-readable, secret-free (for example REFRESH_REJECTED, SCOPE_MISSING, REVOKED) */
    void reconnectRequired(Long tenantId, Long connectionId, String reasonCode);
}
