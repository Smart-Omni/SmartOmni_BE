package com.smartomni.integration.service.tiktok;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * {@code tiktok_sync_checkpoints}: the exclusive {@code update_time} (epoch seconds) up to which a resource of a
 * connection has been polled completely. Tenant-scoped through RLS, so callers must hold the tenant's
 * {@code TenantContext} scope; each method runs in its own transaction where the RLS context is applied.
 */
@Repository
@RequiredArgsConstructor
public class TiktokSyncCheckpointStore {

    public static final String ORDERS = "ORDERS";
    public static final String RETURNS = "RETURNS";
    public static final String CANCELLATIONS = "CANCELLATIONS";

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Optional<Long> find(long connectionId, String resource) {
        List<Long> rows = jdbc.query(
                "SELECT last_update_time FROM tiktok_sync_checkpoints WHERE connection_id = ? AND resource = ?",
                (rs, i) -> rs.getLong(1), connectionId, resource);
        return rows.stream().findFirst();
    }

    /** Moves the checkpoint forward; it never goes back, even if two runs race. */
    @Transactional
    public void advance(long tenantId, long connectionId, String resource, long lastUpdateTime) {
        jdbc.update("""
                INSERT INTO tiktok_sync_checkpoints (tenant_id, connection_id, resource, last_update_time, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (connection_id, resource) DO UPDATE
                SET last_update_time = GREATEST(tiktok_sync_checkpoints.last_update_time, EXCLUDED.last_update_time),
                    updated_at = now()
                """, tenantId, connectionId, resource, lastUpdateTime);
    }
}
